
# Spezifikation Live-Zustellung

## 1. Vertrag
### 1.1 Eingang: was am Exchange chat.delivery ankommt

Am Exchange kommt dasselbe JSON an wie in chat.persist, mit dem __TypeId__-Header, welcher aber hier nicht beachtet wird, um die Bytes unverändert weiterzugeben. Dadurch ist derselbe Aufbau des JSON garantiert.
Der Live-Zustellweg wartet nicht auf die Datenbank.

Die Nachricht wird mit diesem JSON übertragen mit diesen sechs Feldern

| Feld | Typ | Bedeutung |
|---|---|---|
| id | UUID | Eindeutige Nachrichten-ID |
| roomId | UUID | Kennung des Chatraums |
| senderId | String | Benutzer-ID aus Keycloak |
| senderName | String | Anzeigename des Absenders |
| content | String | Nachrichteninhalt |
| sentAt | ISO-8601 | Server-Zeitstempel |

Die Feldnamen werden in camelCase übertragen.

Der Empfänger ist nicht von der Java-Klasse des chat-service abhängig. Der chat-service liest aus dem JSON die Felder id und roomId, um die Nachrichten-ID für das SSE-Ereignis und den Speicher der Instanz sowie die Raum-ID für die Raumfilterung zu verwenden. Die empfangenen Nachrichtenbytes werden ansonsten unverändert an die verbundenen Clients weitergegeben. Eine zusätzliche Umwandlung der vollständigen Nachricht in eine eigene Java-Datenklasse ist nicht erforderlich.


### 1.2 Ausgang: was am Stream hinausgeht

Die Live-Zustellung erfolgt direkt über den chat-service mittels Server-Sent Events.
Der Endpunkt 
```text
GET /stream?roomId={roomId}
```

Der Server verwendet dabei diesen folgenden Content-Type
```text
Content-Type: text/event-stream
```

Die Nachrichten werden im SSE-Format gesendet
```text
id: 0b3c2f5a-1111-4a2b-8c3d-4e5f60718293
data: {"id":"0b3c2f5a-1111-4a2b-8c3d-4e5f60718293","roomId":"9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061","senderId":"keycloak-sub-123","senderName":"Alice Muster","content":"Hallo Welt","sentAt":"2026-09-25T10:15:30.123456Z"}

```

Die id entspricht der Nachrichten-ID. Der data-Inhalt enthält das gleiche JSON für die Nachricht.
Der Client kann beim Verbinden die Kopfzeile Last-Event-ID mitschicken. Was er mit und ohne diese Kopfzeile erhält, steht in 3.6.

Der Server schliesst den Stream nicht von sich aus: Es gibt keine Wartezeit, nach der die Verbindung endet, sie bleibt offen, bis der Client trennt oder die Instanz beendet wird.
Ohne diese Festlegung würde Spring MVC den Stream nach der Standard-Wartezeit von Tomcat schliessen, etwa 30 Sekunden, und die Clients in S3 und S6 mitten im Test trennen. Ist ein Client weg, ohne dass die Verbindung sauber geschlossen wurde, trägt die Instanz ihn aus, sobald das Senden an ihn fehlschlägt.

### 1.3 Woher ich das weiss

Im MessagePublisher Z.35&36 wird über die Methode publish dieselbe ChatMessage durch den JsonMessageConverter von RabbitConfig Z.60 an chat.persist und chat.delivery geschickt.
Gemessen wurde dieses Format bereits für den batch-writer, siehe docs/spec-batch-writer.md, Abschnitt 2.1. Da beide Wege dieselbe ChatMessage mit demselben Konverter erhalten, gilt die Messung auch für chat.delivery.

Exchange prüfen

Der Exchange chat.delivery wird erst angelegt, wenn der chat-service erstmals mit RabbitMQ kommuniziert. Deshalb kann er direkt nach einem Neustart noch fehlen.
Zuerst eine Nachricht über POST /messages senden
```powershell
docker compose exec rabbitmq rabbitmqctl list_exchanges name type
```
Erwartet wird, dass chat.delivery als fanout erscheint.

Queue-Bindungen prüfen
```powershell
docker compose exec rabbitmq rabbitmqctl list_bindings
```
Die bisherige Messung zeigt, dass chat.persist und chat.dlq existieren. In list_bindings gibt es aber keine Zeile mit chat.delivery als Quelle. Zu diesem Zeitpunkt ist also noch keine Queue an chat.delivery gebunden.

SSE-Endpunkt prüfen

Der SSE-Endpunkt gehört zum chat-service und nicht zum web-gateway. Da der chat-service keinen extern veröffentlichten Port besitzt, erfolgt der Test innerhalb des Docker-Netzwerks
```powershell
docker compose exec chat-service sh
```

Anschliessend innerhalb des Containers
```bash
curl -N "http://localhost:8080/stream?roomId=9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061"
```
Messergebnis heute: Der Aufruf liefert 404, weil der Endpunkt noch nicht existiert.

### 1.4 Was nicht versprochen wird

Der Live-Zustellweg übernimmt ausschliesslich die Live-Verteilung.

Nicht enthalten sind:
- Dauerhafte Speicherung. Nichts wird gespeichert, wenn die Instanz beendet wird.
- Zustellung an offline Benutzer ohne verfügbaren Nachrichtenverlauf.
- Eine Obergrenze für den Speicher. Die Instanz hält bis zu 1000 Nachrichten für jeden Raum, der seit ihrem Start eine Nachricht bekam. Die Zahl der Räume ist nicht begrenzt, mit vielen Räumen wächst also auch der Speicher.

Anforderungen der Zustellung:
- Nachrichten eines Absenders werden in ihrer ursprünglichen Reihenfolge zugestellt.
- Jede Nachricht, die eine Instanz aus ihrer Queue abholt, gibt sie jedem verbundenen Client des Raums genau einmal weiter. Die Begründung steht in 3.2.
- Nach einer Unterbrechung kann der Client über Last-Event-ID fehlende Ereignisse nachholen.
- Eine zusätzliche Duplikaterkennung anhand der Nachrichten-ID ist nicht vorgesehen. Das Nachholen beginnt direkt nach der zuletzt empfangenen Event-ID.

## 2. Queues

Der Exchange chat.delivery ist vom Typ fanout.

Jede daran gebundene Queue erhält eine Kopie jeder veröffentlichten Nachricht. Der Exchange kennt keine Räume oder Benutzer.

Jede laufende chat-service-Instanz erhält deshalb eine eigene Queue.

Aufbau Queues

| Eigenschaft | Wert |
|---|---|
| Typ | Classic Queue |
| Durable | false |
| Exclusive | true |
| Auto-delete | true |
| Binding | chat.delivery |
| Name | Automatisch generiert (amq.gen-...) |
| x-max-length | 1000 |
| x-overflow | drop-head |

Der Queue-Name wird automatisch erzeugt und ist nicht im Quellcode fest hinterlegt.

Jede chat-service-Instanz erhält dadurch alle veröffentlichten Nachrichten unabhängig von den anderen Instanzen.
Eine gemeinsame Queue wäre falsch, da RabbitMQ Nachrichten zwischen den Instanzen verteilen würde. Dadurch würde nicht jede Instanz eine Kopie jeder Nachricht erhalten.

Die Queues werden bei einem Neustart verworfen. Was eine Instanz während ihres Ausfalls verpasst, kann sie selbst nicht nachliefern, denn nach dem Neustart ist ihr Puffer leer. Nachliefern kann nur eine Instanz, die während des Ausfalls lief: Sie hat über den Fanout jede Nachricht jedes Raums erhalten, ihre Liste enthält also auch die Nachrichten aus dieser Zeit, die letzten 1000 pro Raum.

## 3. Verhalten

### 3.1 Normalfall

Der normale Ablauf

1. Ein Benutzer sendet eine Nachricht.
2. Der chat-service nimmt die Nachricht entgegen.
3. Der chat-service vergibt eine UUID und einen Server-Zeitstempel.
4. Die Nachricht wird auf chat.persist und chat.delivery veröffentlicht.
5. Der batch-writer schreibt in die Datenbank für dauerhafte Speicherung.
6. Der chat-service empfängt die Nachricht über seine eigene Queue.
7. Die Nachricht wird anhand ihrer roomId dem passenden Raum zugeordnet.
8. Verbundene Clients erhalten die Nachricht über den SSE-Stream.

Das Schreiben in die Datenbank und der Live-Zustellweg sind unabhängig voneinander.

Falls die Datenbank nicht erreichbar ist, wird somit nicht die Live-Zustellung eingeschränkt.

### 3.2 Raumfilter (S3)
Der Fanout-Exchange verteilt jede Nachricht an alle chat-service-Instanzen.
Das ist notwendig, weil jede Instanz eigene SSE-Verbindungen verwaltet.
Die Raumfilterung erfolgt innerhalb des chat-service anhand der roomId.
Eine Nachricht wird nur an Clients weitergegeben, die den entsprechenden Raum abonniert haben.

Warum auf dem Live-Weg jede Nachricht genau einmal und die eines Absenders in seiner Reihenfolge ankommt:
- Eine Queue pro Instanz: Der Fanout-Exchange legt jede Nachricht genau einmal in jede gebundene Queue. Jede Instanz hat genau eine Queue, also erreicht jede Nachricht jede Instanz genau einmal.
- Ein Abholer pro Queue: Die Queue ist exclusive. Nur der eine Consumer der eigenen Instanz liest daraus. Es gibt also keine zweite Stelle, die dieselbe Nachricht abholt oder die Reihenfolge durcheinanderbringt.
- Die Queue gibt die Nachrichten in der Reihenfolge aus, in der sie hineinkamen. Die Nachrichten eines Absenders kommen in der Reihenfolge in die Queue, in der der chat-service sie veröffentlicht hat, und werden in derselben Reihenfolge abgeholt.

Die Instanz gibt jede abgeholte Nachricht einmal an jeden verbundenen Client des Raums weiter. Damit erhält jeder Client jede Nachricht genau einmal, die eines Absenders in seiner Reihenfolge.

### 3.3 Instanz weg (S4)

Wenn eine chat-service-Instanz beendet wird oder abstürzt:
- Die RabbitMQ-Verbindung wird geschlossen.
- Die exklusive Queue wird entfernt.
- Die SSE-Verbindungen dieser Instanz werden unterbrochen.
- Andere chat-service-Instanzen laufen unverändert weiter.
- Neue Instanzen erstellen beim Start eigene temporäre Queues.

Nachrichten während des Ausfalls werden nicht über die entfernte Queue zugestellt.

Verbindet sich ein Client danach mit Last-Event-ID neu, hängt das Nachholen davon ab, bei welcher Instanz er landet. Eine Instanz, die durchgehend lief, kennt seine ID und liefert die fehlenden Nachrichten nach, sofern sie noch unter den letzten 1000 des Raums sind. Eine neu gestartete Instanz kennt die ID nicht. Der Client erhält dann ihre Liste, also nur die Nachrichten seit ihrem Start (siehe 3.6). Was dazwischen lag, fehlt auf dem Live-Weg, steht aber in der Datenbank. Bei welcher Instanz er landet, kann der Client nicht wählen.

### 3.4 Datenbank und batch-writer weg (S5)

Wenn PostgreSQL und der batch-writer gestoppt sind:
- Der chat-service veröffentlicht Nachrichten weiterhin auf beide Wege.
- Die Nachrichten bleiben in chat.persist liegen, weil der batch-writer sie nicht abholt und nicht speichern kann.
- Der Live-Zustellweg empfängt Nachrichten unabhängig davon.
- Verbundene Clients erhalten weiterhin Nachrichten über SSE.

Sobald PostgreSQL und der batch-writer wieder laufen, holt der batch-writer die wartenden Nachrichten aus chat.persist und schreibt sie in die Datenbank.

Der Live-Zustellweg greift nicht auf die Datenbank zu.

Die Live-Zustellung verwendet nur die Daten aus RabbitMQ und dem internen Speicher der Instanz.


### 3.5 Instanz hängt (S6)

Verarbeitet eine chat-service-Instanz Nachrichten nicht rechtzeitig, sammeln sich diese in ihrer Queue.
Die Queue ist auf maximal 1000 Nachrichten begrenzt.
Bei Überschreitung der Grenze wird durch x-overflow = drop-head die älteste Nachricht entfernt.

Begründung:

Die Begrenzung verhindert, dass der RabbitMQ-Broker durch eine dauerhaft hängende Instanz mit immer mehr Nachrichten belastet wird.

Der Verlust älterer Nachrichten ist vertretbar, da der chat-service jede Nachricht zusätzlich auf chat.persist veröffentlicht. Der batch-writer übernimmt die dauerhafte Speicherung.

Verhalten:

- Die andere chat-service-Instanz verarbeitet ihre Nachrichten weiterhin.
- Der Client an der anderen Instanz erhält alle 5000 gesendeten Nachrichten.
- Solange die Verbindung der hängenden Instanz besteht, wächst ihre Queue, aber nie über 1000 Nachrichten. drop-head entfernt jeweils die älteste.
- Eine angehaltene Instanz schickt RabbitMQ keine Heartbeats mehr. Nach etwa zwei Minuten schliesst RabbitMQ deshalb ihre Verbindung (Messung unten). Weil die Queue exklusiv ist, wird sie dabei gelöscht, samt allen Nachrichten darin. Bis die Instanz fortgesetzt wird, hat sie keine Queue mehr, und der Fanout gibt ihr nichts.
- Nach dem Fortsetzen merkt die Instanz, dass ihre Verbindung weg ist. Sie baut eine neue auf, legt eine neue Queue an, bindet sie an chat.delivery und holt wieder ab. Ab dann stellt sie neue Nachrichten wieder zu.
- Ihr fehlen alle Nachrichten, die in der gelöschten Queue lagen, und alle, die veröffentlicht wurden, während sie keine Queue hatte. Sie stehen auch nicht in ihrer Liste. Holt ein Client bei dieser Instanz mit Last-Event-ID nach, fehlen ihm diese Nachrichten, ohne dass er es merkt. Vollständig nachholen kann er nur bei einer Instanz, die durchgehend lief (siehe 3.3).
- Ist die Pause kürzer, bleibt die Verbindung und damit die Queue bestehen. Dann stellt die Instanz nach dem Fortsetzen die noch vorhandenen, höchstens 1000 Nachrichten zu. Ältere, die drop-head entfernt hat, fehlen ihr.

Messung (am batch-writer, bei dem wie beim chat-service kein Heartbeat eingestellt ist):

```powershell
docker compose pause batch-writer
for ($i = 0; $i -le 6; $i++) { Get-Date -Format "HH:mm:ss"; docker compose exec rabbitmq rabbitmqctl list_connections name state timeout; Start-Sleep -Seconds 20 }
docker compose unpause batch-writer
Start-Sleep -Seconds 10
docker compose exec rabbitmq rabbitmqctl list_connections name state timeout
```

Ergebnis:
- Die Spalte timeout zeigt 60, das ist der Spring-Standard.
- Nach dem Pausieren stand die Verbindung noch 1 Minute 48 Sekunden als running da. Bei der nächsten Abfrage, 2 Minuten 9 Sekunden nach dem Pausieren, war sie weg. RabbitMQ schliesst die Verbindung also nach etwa zwei Minuten ohne Heartbeats.
- 10 Sekunden nach dem Fortsetzen stand eine neue Verbindung mit einem anderen Port da (vorher 44882, nachher 59862). Spring hat sich also von selbst neu verbunden.
- Ob die neue Queue des chat-service auch wieder an chat.delivery gebunden wird, kann der batch-writer nicht zeigen. Das prüft S6 mit der Nachricht nach-pause.


### 3.6 Neuverbindung (S7)

Verliert ein Client seine SSE-Verbindung, kann er diese selbstständig wiederherstellen.
Der Client verwendet dazu den HTTP-Header
```text
Last-Event-ID: 0b3c2f5a-1111-4a2b-8c3d-4e5f60718293
```

Der chat-service hält pro Raum eine Liste der letzten 1000 Nachrichten im Speicher.

Es gibt zwei Fälle:

**Fall 1: Der Client verbindet sich ohne Last-Event-ID.**
Der Client erhält nichts aus der Liste, sondern nur noch die Nachrichten, die ab jetzt neu eintreffen, live.

**Fall 2: Der Client verbindet sich mit Last-Event-ID.**

1. Der Client verbindet sich erneut mit GET /stream?roomId=....
2. Er übermittelt seine letzte empfangene Nachrichten-ID über Last-Event-ID.
3. Der Server ermittelt die Position dieser Nachricht im Speicher der Instanz.
4. Alle nachfolgenden verfügbaren Nachrichten werden erneut zugestellt.
5. Anschliessend erhält der Client neue Nachrichten wieder live.

Wenn die Instanz die id nicht kennt, werden alle Nachrichten aus der Liste geladen und danach neue Nachrichten live zugestellt.

Neue Nachricht während des Nachholens: Die Instanz liest die Liste, schickt dem Client die fehlenden Nachrichten und trägt ihn erst danach als Empfänger ein, und das alles unter derselben Sperre pro Raum, die sie auch hält, während sie eine neue Nachricht aus der Queue in die Liste einfügt und an die eingetragenen Clients weitergibt.
Eine neue Nachricht kommt damit entweder vor dem Nachholen in die Liste und wird nachgeliefert, oder sie kommt danach und geht live hinaus, nie beides und nie keins. Weil die nachgelieferten Nachrichten vollständig verschickt sind, bevor die erste live hinausgeht, bleibt auch die Reihenfolge erhalten. Der Preis: Während eine Instanz nachliefert, wartet die Zustellung neuer Nachrichten in diesem Raum kurz.

Darum ist keine zusätzliche Duplikaterkennung notwendig: Der Server liefert nur Ereignisse nach der angegebenen Last-Event-ID nach, und die Sperre verhindert, dass eine Nachricht zugleich nachgeliefert und live gesendet wird.

Die Reihenfolge der Nachrichten bleibt erhalten.

Der interne Speicher ist flüchtig und wird beim Neustart einer Instanz gelöscht. Nachrichten, die nicht mehr im Puffer vorhanden sind, können über diesen Mechanismus nicht nachgeholt werden.


## 4. Abnahmekriterien

S1 und S8 brauchen keinen laufenden Stack. Für S1 muss aber Docker Desktop laufen, weil die Tests ein echtes RabbitMQ starten. S3 bis S7 setzen voraus, dass der Stack aus S2 läuft.

Alle Befehle werden in PowerShell im Wurzelverzeichnis ausgeführt.

Vor den Tests einmal die Räume festlegen und die zwei Skripte anlegen. Die Skripte entstehen so vor dem Start der Clients, damit deren --max-time nicht schon abläuft, während man sie erstellt.

Räume festlegen. PowerShell setzt die Variablen in Befehlen mit doppelten Anführungszeichen von selbst ein:
```powershell
$RAUM_A = "9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061"
$RAUM_B = "9a8b7c6d-2222-4e3f-9a0b-00000000000b"
```

Nachrichten-Skript erstellen (verwendet in S3, S5, S6 und S7):
```powershell
@'
for i in $(seq 1 ${ANZAHL:-1000}); do
  curl -s -o /dev/null -X POST -H 'Content-Type: application/json' -d '{"roomId":"'"$RAUM"'","senderId":"'"${SENDER:-sub-1}"'","senderName":"Test","content":"Nachricht '"$i"'"}' http://localhost:8080/messages
done
echo fertig
'@ | Set-Content -Encoding ascii $env:TEMP\last.sh
```

Prüf-Skript für die Reihenfolge erstellen (verwendet in S3 und S7). Es nimmt nur die Zeilen des Absenders SENDER aus der Datei DATEI, liest die Nummern aus «Nachricht N» und prüft, ob sie lückenlos 1, 2, 3, … aufsteigen. Am Schluss gibt es OK oder FALSCH und die letzte Nummer aus:
```powershell
@'
grep "\"senderId\":\"$SENDER\"" "$DATEI" | grep -o 'Nachricht [0-9]*' | awk '{ if ($2 != n+1) f=1; n=$2 } END { print (f ? "FALSCH" : "OK"), n+0 }'
'@ | Set-Content -Encoding ascii $env:TEMP\reihenfolge.sh
```

### S1 – Tests ausführen

Szenario : S1 
Prüfung : .\mvnw clean test im Wurzelverzeichnis
Erwartetes Ergebnis : Ein Lauf, alles grün 

```powershell
.\mvnw clean test
```

### S2 – Frischer Klon und Docker-Start

Szenario : S2 
Prüfung : Frischer Klon, .env aus .env.example erstellen und docker compose up -d --build ausführen 
Erwartetes Ergebnis: Alle Dienste laufen. Nur das Gateway veröffentlicht einen Port, laut Absprache mit dem Lehrer.

```powershell
cp .env.example .env
docker compose up -d --build
```

Dienste überprüfen
```powershell
docker compose ps
```

Veröffentlichte Ports überprüfen
```powershell
docker compose config | Select-String published 
```
Erwartet wird: Select-String findet genau eine Zeile, die des Gateways (laut Absprache mit dem Lehrer).

### S3 – Zwei Instanzen und Raumfilterung

Szenario : S3 
Prüfung  : Zwei Instanzen (--scale chat-service=2), an jeder ein Client in Raum A, ein dritter Client in Raum B. Je 200 Nachrichten für Raum A über jede Instanz senden. 
Erwartetes Ergebnis : Beide Clients in Raum A erhalten alle 400 Nachrichten, jede genau einmal, die eines Absenders in seiner Reihenfolge. Der Client in Raum B erhält keine. |

Zwei Instanzen starten:
```powershell
docker compose up -d --scale chat-service=2
```

Clients an Instanz 1 und Instanz 2 sowie im Raum B verbinden
```powershell
docker compose exec -d --index 1 chat-service sh -c "curl -sN --max-time 120 'http://localhost:8080/stream?roomId=$RAUM_A' > /tmp/client-a1.txt"
```

```powershell
docker compose exec -d --index 2 chat-service sh -c "curl -sN --max-time 120 'http://localhost:8080/stream?roomId=$RAUM_A' > /tmp/client-a2.txt"
```

```powershell
docker compose exec -d --index 2 chat-service sh -c "curl -sN --max-time 120 'http://localhost:8080/stream?roomId=$RAUM_B' > /tmp/client-b.txt"
```

200 Nachrichten in Raum A über Instanz 1 senden
```powershell
Get-Content $env:TEMP\last.sh | docker compose exec -T --index 1 -e RAUM=$RAUM_A -e ANZAHL=200 -e SENDER=sub-1 chat-service sh -c "tr -d '\r' | sh"
```

200 Nachrichten in Raum A über Instanz 2 senden:
```powershell
Get-Content $env:TEMP\last.sh | docker compose exec -T --index 2 -e RAUM=$RAUM_A -e ANZAHL=200 -e SENDER=sub-2 chat-service sh -c "tr -d '\r' | sh"
```

Empfangene Ereignisse zählen
```powershell
docker compose exec --index 1 chat-service grep -c '^id:' /tmp/client-a1.txt
```

```powershell
docker compose exec --index 2 chat-service grep -c '^id:' /tmp/client-a2.txt
```

```powershell
docker compose exec --index 2 chat-service grep -c '^id:' /tmp/client-b.txt
```

Erwartet werden:
- Client A1: 400 Ereignisse.
- Client A2: 400 Ereignisse.
- Client B: 0 Ereignisse.

Keine Doppelten: Anzahl verschiedener IDs zählen
```powershell
docker compose exec --index 1 chat-service sh -c "grep '^id:' /tmp/client-a1.txt | sort -u | wc -l"
```

```powershell
docker compose exec --index 2 chat-service sh -c "grep '^id:' /tmp/client-a2.txt | sort -u | wc -l"
```
Erwartet wird bei beiden 400.

Reihenfolge je Absender prüfen
```powershell
Get-Content $env:TEMP\reihenfolge.sh | docker compose exec -T --index 1 -e DATEI=/tmp/client-a1.txt -e SENDER=sub-1 chat-service sh -c "tr -d '\r' | sh"
Get-Content $env:TEMP\reihenfolge.sh | docker compose exec -T --index 1 -e DATEI=/tmp/client-a1.txt -e SENDER=sub-2 chat-service sh -c "tr -d '\r' | sh"
Get-Content $env:TEMP\reihenfolge.sh | docker compose exec -T --index 2 -e DATEI=/tmp/client-a2.txt -e SENDER=sub-1 chat-service sh -c "tr -d '\r' | sh"
Get-Content $env:TEMP\reihenfolge.sh | docker compose exec -T --index 2 -e DATEI=/tmp/client-a2.txt -e SENDER=sub-2 chat-service sh -c "tr -d '\r' | sh"
```
Erwartet wird viermal: OK 200.

### S4 – Instanz entfernen und Queues überprüfen

Szenario: S4 
Prüfung : Zwei Instanzen laufen, dann zurück auf eine (--scale chat-service=1).
Erwartetes Ergebnis : Vorher genau zwei Queues am Fanout-Exchange, je mit einem Consumer. Nach spätestens 30 Sekunden nur noch eine. chat.persist und chat.dlq bleiben. 

Zwei Instanzen starten
```powershell
docker compose up -d --scale chat-service=2
```

Queues überprüfen
```powershell
docker compose exec rabbitmq rabbitmqctl list_queues name durable auto_delete arguments consumers
```

Queue-Bindings überprüfen:
```powershell
docker compose exec rabbitmq rabbitmqctl list_bindings
```

Auf eine Instanz reduzieren:
```powershell
docker compose up -d --scale chat-service=1
```

30 Sekunden warten und erneut überprüfen
```powershell
Start-Sleep -Seconds 30
docker compose exec rabbitmq rabbitmqctl list_queues name durable auto_delete arguments consumers
```

Erwartet wird
- Vorher zwei Queues amq.gen-… mit consumers 1.
- Danach nur noch eine Queue amq.gen-….
- chat.persist und chat.dlq stehen ebenfalls in der Liste und bleiben bestehen.

### S5 – Datenbank und batch-writer gestoppt

Szenario: S5
Prüfung : batch-writer und postgres gestoppt, 100 Nachrichten gesendet.
Erwartetes Ergebnis : Alle 100 erscheinen nach spätestens 5 s beim Client; nach dem Neustart beider Dienste stehen alle 100 in der Tabelle.

Client verbinden
```powershell
docker compose exec -d --index 1 chat-service sh -c "curl -sN --max-time 60 'http://localhost:8080/stream?roomId=$RAUM_A' > /tmp/client-s5.txt"
```

batch-writer und PostgreSQL stoppen:
```powershell
docker compose stop batch-writer postgres
```

100 Nachrichten senden (last.sh aus Abschnitt 4). Der Absender bekommt die Uhrzeit angehängt, damit ein zweiter Lauf nicht die Nachrichten des ersten mitzählt:
```powershell
$s5 = "sub-s5-" + (Get-Date -Format "HHmmss")
Get-Content $env:TEMP\last.sh | docker compose exec -T --index 1 -e RAUM=$RAUM_A -e ANZAHL=100 -e SENDER=$s5 chat-service sh -c "tr -d '\r' | sh"
```

5 Sekunden warten, dann die empfangenen Ereignisse zählen
```powershell
Start-Sleep -Seconds 5
docker compose exec --index 1 chat-service grep -c '^id:' /tmp/client-s5.txt
```
Erwartet werden 100 Ereignisse.

Dienste starten und etwa 20 Sekunden warten, bis der batch-writer die wartenden Nachrichten geschrieben hat
```powershell
docker compose start postgres batch-writer
Start-Sleep -Seconds 20
```

Anschliessend überprüfen, ob alle 100 Nachrichten in der Datenbank gespeichert wurden:
```powershell
docker compose exec postgres psql -U chat -d chat -c "SELECT COUNT(*) FROM message WHERE sender_id = '$s5';"
```

Erwartet werden 100 gespeicherte Nachrichten.

### S6 – Angehaltene Instanz und Queue-Begrenzung
Szenario: S6 
Prüfung: Eine von zwei Instanzen angehalten (docker pause), 5000 Nachrichten gesendet, danach fortgesetzt.
Erwartetes Ergebnis: Ihre Queue hält nie mehr als 1000 Nachrichten; der Client an der anderen Instanz erhält alle 5000; nach dem Fortsetzen stellt die Instanz neue Nachrichten wieder zu.

Zwei Instanzen starten
```powershell
docker compose up -d --scale chat-service=2
```

Clients verbinden. --max-time ist 900, weil 5000 Nachrichten mehrere Minuten dauern und die Pause mindestens 3 Minuten dauert:
```powershell
docker compose exec -d --index 1 chat-service sh -c "curl -sN --max-time 900 'http://localhost:8080/stream?roomId=$RAUM_A' > /tmp/client-s6-a.txt"
```

```powershell
docker compose exec -d --index 2 chat-service sh -c "curl -sN --max-time 900 'http://localhost:8080/stream?roomId=$RAUM_A' > /tmp/client-s6-b.txt"
```

Instanz 2 pausieren und die Uhrzeit merken:
```powershell
$instanz2 = docker compose ps --format "{{.Name}}" chat-service | Where-Object { $_ -like '*-2' }
docker pause $instanz2
$pauseStart = Get-Date
```

Queue-Grösse beobachten: Diese Schleife in einem zweiten PowerShell-Fenster starten, ebenfalls im Wurzelverzeichnis, sonst findet docker compose die Datei docker-compose.yml nicht. Sie muss laufen, bevor gesendet wird, und fragt während des ganzen Sendens alle 10 Sekunden ab. Beenden mit Ctrl+C.
```powershell
while ($true) { docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers; Start-Sleep -Seconds 10 }
```

5000 Nachrichten über Instanz 1 senden (last.sh aus Abschnitt 4):
```powershell
Get-Content $env:TEMP\last.sh | docker compose exec -T --index 1 -e RAUM=$RAUM_A -e ANZAHL=5000 -e SENDER=sub-s6 chat-service sh -c "tr -d '\r' | sh"
```

Warten, bis die Pause mindestens 3 Minuten gedauert hat. Gemessen schliesst RabbitMQ die Verbindung nach etwa 2 Minuten (siehe 3.5). So ist sicher, dass die Queue weg ist, egal wie schnell das Senden war:
```powershell
while (((Get-Date) - $pauseStart).TotalSeconds -lt 180) { Start-Sleep -Seconds 5 }
```

Im zweiten Fenster wird erwartet:
- Die Queue der pausierten Instanz ist die amq.gen-…, deren messages ansteigt. Sie zeigt nie mehr als 1000.
- Etwa 2 Minuten nach dem Pausieren verschwindet sie aus der Liste. Danach steht nur noch eine amq.gen-… da.

Empfangene Nachrichten der anderen Instanz zählen
```powershell
docker compose exec --index 1 chat-service grep -c '^id:' /tmp/client-s6-a.txt
```
Erwartet werden 5000 Ereignisse.

Die pausierte Instanz fortsetzen, 15 Sekunden warten und die Queues abfragen:
```powershell
docker unpause $instanz2
Start-Sleep -Seconds 15
docker compose exec rabbitmq rabbitmqctl list_queues name messages consumers
```
Erwartet werden wieder zwei amq.gen-… mit consumers 1. Die neue Queue der fortgesetzten Instanz hat einen neuen Namen und messages 0.

Zählen, wie viele der 5000 Nachrichten beim Client an Instanz 2 angekommen sind:
```powershell
docker compose exec --index 2 chat-service grep -c 'sub-s6' /tmp/client-s6-b.txt
```
Erwartet wird 0. Die Nachrichten lagen in der gelöschten Queue oder kamen, als die Instanz keine Queue hatte (siehe 3.5).

Prüfen, ob die Instanz wieder zustellt: eine neue Nachricht mit SENDER=nach-pause senden, 5 Sekunden warten und beim Client an Instanz 2 zählen:
```powershell
Get-Content $env:TEMP\last.sh | docker compose exec -T --index 1 -e RAUM=$RAUM_A -e ANZAHL=1 -e SENDER=nach-pause chat-service sh -c "tr -d '\r' | sh"
Start-Sleep -Seconds 5
docker compose exec --index 2 chat-service grep -c 'nach-pause' /tmp/client-s6-b.txt
```
Erwartet wird 1.

### S7 – Neuverbindung mit Last-Event-ID

Szenario: S7
Prüfung : Client trennt nach einer Nachricht, 50 Nachrichten gesendet, nach 10 s Neuverbindung mit Last-Event-ID.
Erwartetes Ergebnis: Der Client erhält genau die 50 fehlenden, in ihrer Reihenfolge, keine doppelt, danach wieder live. 

1. Client im Hintergrund verbinden. Nach 20 Sekunden trennt er sich von selbst:
```powershell
docker compose exec -d --index 1 chat-service sh -c "curl -sN --max-time 20 'http://localhost:8080/stream?roomId=$RAUM_A' > /tmp/s7-vorher.txt"
```

2. Eine Nachricht senden:
```powershell
Get-Content $env:TEMP\last.sh | docker compose exec -T --index 1 -e RAUM=$RAUM_A -e ANZAHL=1 -e SENDER=sub-s7-vorher chat-service sh -c "tr -d '\r' | sh"
```

3. Ihre ID ablesen und in einer Variable merken:
```powershell
docker compose exec --index 1 chat-service grep '^id:' /tmp/s7-vorher.txt
$letzteId = ((docker compose exec -T --index 1 chat-service grep '^id:' /tmp/s7-vorher.txt) -replace '^id:\s*', '').Trim()
```
Erwartet wird genau eine Zeile `id: …`.

4. Warten, bis die 20 Sekunden um sind. Danach ist der Client getrennt:
```powershell
Start-Sleep -Seconds 20
```

5. 50 Nachrichten senden:
```powershell
Get-Content $env:TEMP\last.sh | docker compose exec -T --index 1 -e RAUM=$RAUM_A -e ANZAHL=50 -e SENDER=sub-s7 chat-service sh -c "tr -d '\r' | sh"
```

6. 10 Sekunden warten:
```powershell
Start-Sleep -Seconds 10
```

7. Mit der abgelesenen ID im Hintergrund neu verbinden:
```powershell
docker compose exec -d --index 1 chat-service sh -c "curl -sN --max-time 20 -H 'Last-Event-ID: $letzteId' 'http://localhost:8080/stream?roomId=$RAUM_A' > /tmp/s7-nachher.txt"
```

8. Eine weitere Nachricht mit SENDER=live senden, für «danach wieder live»:
```powershell
Get-Content $env:TEMP\last.sh | docker compose exec -T --index 1 -e RAUM=$RAUM_A -e ANZAHL=1 -e SENDER=live chat-service sh -c "tr -d '\r' | sh"
```

9. 5 Sekunden warten, dann zählen und prüfen. Die live gesendete Nachricht wird mit reihenfolge.sh und SENDER=live gezählt, so wird genau der Absender live gesucht:
```powershell
Start-Sleep -Seconds 5
docker compose exec --index 1 chat-service grep -c '^id:' /tmp/s7-nachher.txt
docker compose exec --index 1 chat-service sh -c "grep '^id:' /tmp/s7-nachher.txt | sort -u | wc -l"
Get-Content $env:TEMP\reihenfolge.sh | docker compose exec -T --index 1 -e DATEI=/tmp/s7-nachher.txt -e SENDER=sub-s7 chat-service sh -c "tr -d '\r' | sh"
Get-Content $env:TEMP\reihenfolge.sh | docker compose exec -T --index 1 -e DATEI=/tmp/s7-nachher.txt -e SENDER=live chat-service sh -c "tr -d '\r' | sh"
```

Erwartet werden:
- 51 Ereignisse, also 50 nachgeholte und eine live.
- 51 verschiedene IDs, also keine doppelt.
- Reihenfolge von sub-s7: OK 50.
- Die live gesendete Nachricht: OK 1.

### S8 – Quelltext und Codequalität

Szenario : S8
Prüfung  : Quelltext von chat-service/ überprüfen. Regeln aus CLAUDE.md.
Erwartetes Ergebnis: Keine Streams, Kommentar über jeder Klasse und Methode; .env nicht im Repo. 

Java-Streams überprüfen
```powershell
git grep -nE '\.stream\(\)|\.parallelStream\(\)|Collectors\.|\.forEach\(' -- chat-service/src
```
Erwartet wird keine Ausgabe.

Überprüfen, ob .env im Repository enthalten ist:
```powershell
git ls-files | Select-String '(^|/)\.env$'
```
Erwartet wird keine Ausgabe.

Überprüfen, ob .env in .gitignore eingetragen ist
```powershell
Select-String '^\.env$' .gitignore
```
Erwartet wird eine Zeile mit .env.

Die Datei .env.example muss weiterhin im Repository vorhanden sein
```powershell
git ls-files | Select-String '(^|/)\.env\.example$'
```
Erwartet wird die Datei .env.example.

Die Kommentare über jeder Klasse und jeder Methode werden von Hand geprüft, Datei für Datei.
