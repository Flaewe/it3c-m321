# Umsetzungsplan  Live-Zustellung

**Spezifikation:** [`spec-live-zustellung.md`](spec-live-zustellung.md)


## Globale Vorgaben

Gelten für jeden Schritt und stehen deshalb nicht in jedem Auftrag einzeln:

- Gebaut wird im bestehenden Modul chat-service, neue Klassen im Paket
  ch.benedict.m321.chatservice.delivery. Kein neues Modul, kein neuer Dienst.
- Java 21, Spring Boot 3.5.16, Spring MVC (spring-boot-starter-web). Kein WebFlux.
- Code auf Englisch, alles andere auf Deutsch. Kommentar über jeder Klasse und jeder
  Methode, der erklärt *warum* sie existiert.
- Keine Streams, keine verschachtelten Aufrufe. Ein Ergebnis pro Zeile in eine benannte
  Variable. Gilt auch in Tests.
- Lombok nur für @Slf4j und @RequiredArgsConstructor. Datenklassen sind record.
- Keine Vorrats-Abstraktionen. Kein Interface mit einer einzigen Implementierung.
- Kein Port-Mapping für den chat-service in ocker-compose.yml.
- Keine Geheimnisse im Repository. Neue Variablen kommen mit Beispielwert in .env.example.
- Tests mit RabbitMQ verwenden Testcontainers mit demselben Abbild wie die bestehenden
  Tests: rabbitmq:3.13-management. Docker Desktop muss laufen.
- Alle Befehle laufen in PowerShell im Wurzelverzeichnis. -Dtest=… steht in Anführungszeichen,
  weil PowerShell sonst Kommas und Punkte darin zerlegt.

## Warum diese Reihenfolge

Zuerst kommen die zwei Teile, die sich ohne Broker und ohne HTTP prüfen lassen: die Liste
eines Raums (Schritt 1) und das Lesen der Nachricht (Schritt 2). Sie sind der Kern von 3.6 und
1.1, und ihre Tests laufen in Sekunden.

Dann wird die Instanz an RabbitMQ angeschlossen (Schritt 3). Die Neuverbindung bekommt einen
eigenen Schritt (Schritt 4), weil sie die riskanteste Stelle ist: In 3.5 haben wir gemessen,
dass RabbitMQ eine hängende Verbindung schliesst. Wenn die Instanz danach die neue Queue nicht
benutzt, stellt sie nie wieder etwas zu, und kein anderer Test würde es merken.

Erst danach kommt der Stream nach aussen (Schritt 5), dann das Nachholen mit Last-Event-ID und
der Sperre (Schritt 6), dann der Test mit zwei Instanzen (Schritt 7). Zuletzt Doku und die
Szenarien von Hand (Schritt 8).

Umgekehrt, also mit dem SSE-Endpunkt anfangen, wäre verlockend, weil man dann schnell etwas im
Browser sieht. Dann würde man aber die Liste und das Lesen über HTTP testen, und ein Fehler
wäre schwer zu finden.


## Schritt 1 — Die Liste eines Raums

Ziel: Eine Klasse RoomChannel hält für einen Raum die letzten 1000 Ereignisse und
beantwortet, was ein Client beim Verbinden aus der Liste bekommt.

Warum hier: Das ist der Kern von 3.6 und lässt sich ohne Broker, ohne HTTP und ohne Spring
prüfen. Alle späteren Schritte bauen darauf.

**KI-Auftrag im Wortlaut:**

> Lies `CLAUDE.md` und in `docs/spec-live-zustellung.md` den Abschnitt 3.6. Arbeite im Modul
> `chat-service`, Paket `ch.benedict.m321.chatservice.delivery`. Lege genau diese zwei Typen an:
>
> 1. `record DeliveryEvent(UUID id, UUID roomId, String json)`. Ein Ereignis für den Stream:
>    die Nachrichten-ID, die Raum-ID und das JSON der Nachricht als unveränderter Text.
> 2. Klasse `RoomChannel` für genau einen Raum. Sie hält die Ereignisse in einer `ArrayList`
>    in der Reihenfolge, in der sie hinzugefügt wurden. Die Grenze steht als Konstante
>    `MAX_EVENTS = 1000` in der Klasse. Methoden:
>    - `add(DeliveryEvent event)` hängt hinten an. Sind danach mehr als 1000 Einträge drin,
>      wird der vorderste entfernt.
>    - `List<DeliveryEvent> eventsAfter(String lastEventId)` beantwortet, was ein Client aus
>      der Liste bekommt. Genau drei Fälle:
>      - `lastEventId` ist `null`: leere Liste. Ein Client ohne Last-Event-ID bekommt nichts
>        aus der Liste, nur noch live.
>      - Ein Ereignis mit dieser ID steht in der Liste: alle Ereignisse danach, in Reihenfolge.
>        Verglichen wird `event.id().toString()` mit `lastEventId`.
>      - Kein Ereignis mit dieser ID: die ganze Liste.
>    - Zurückgegeben wird immer eine neue Liste, nie die interne.
>    - Beide Methoden sind `synchronized`. Die Sperre wird in Schritt 5 und 6 gebraucht und
>      gehört von Anfang an dazu. Erkläre im Kommentar in zwei Sätzen, was `synchronized` hier
>      bewirkt.
>
> Noch kein Spring-Bean, kein HTTP, kein RabbitMQ. Eine `ArrayList` mit `remove(0)` ist bei
> 1000 Einträgen schnell genug und leichter zu lesen als ein Ringpuffer.
>
> Schreib dazu `RoomChannelTest` (JUnit 5, ohne Spring) mit diesen Fällen:
> - `eventsAfter(null)` ist leer, auch wenn Ereignisse drin sind,
> - eine ID aus der Mitte liefert genau die Ereignisse danach, in Reihenfolge,
> - die ID des letzten Ereignisses liefert eine leere Liste,
> - eine unbekannte ID liefert die ganze Liste,
> - nach 1001 Ereignissen sind 1000 drin, das erste ist weg, und seine ID gilt als unbekannt.
>

**Test:**
```powershell
.\mvnw -pl chat-service test "-Dtest=RoomChannelTest"
```
Erwartet: 5 Tests, alle grün.

**Geprüft und korrigiert:**
>> .\mvnw -pl chat-service test "-Dtest=RoomChannelTest"
remote: Enumerating objects: 21, done.
remote: Counting objects: 100% (21/21), done.
remote: Compressing objects: 100% (8/8), done.
remote: Total 21 (delta 3), reused 21 (delta 3), pack-reused 0 (from 0)
Unpacking objects: 100% (21/21), 4.74 KiB | 13.00 KiB/s, done.
From https://github.com/Flaewe/it3c-m321
   2e64a65..a12ae5c  main       -> origin/main
Updating 2e64a65..a12ae5c
Fast-forward
 .../m321/chatservice/delivery/DeliveryEvent.java   |  16 +++
 .../m321/chatservice/delivery/RoomChannel.java     | 103 ++++++++++++++++++++
 .../m321/chatservice/delivery/RoomChannelTest.java | 107 +++++++++++++++++++++
 3 files changed, 226 insertions(+)
 create mode 100644 chat-service/src/main/java/ch/benedict/m321/chatservice/delivery/DeliveryEvent.java
 create mode 100644 chat-service/src/main/java/ch/benedict/m321/chatservice/delivery/RoomChannel.java
 create mode 100644 chat-service/src/test/java/ch/benedict/m321/chatservice/delivery/RoomChannelTest.java
[INFO] Scanning for projects...
[INFO] 
[INFO] -------------------< ch.benedict.m321:chat-service >--------------------
[INFO] Building chat-service 0.1.0-SNAPSHOT
[INFO]   from pom.xml
[INFO] --------------------------------[ jar ]---------------------------------
[INFO] 
[INFO] --- resources:3.3.1:resources (default-resources) @ chat-service ---
[INFO] Copying 1 resource from src\main\resources to target\classes
[INFO] Copying 0 resource from src\main\resources to target\classes
[INFO] 
[INFO] --- compiler:3.14.1:compile (default-compile) @ chat-service ---
[INFO] Recompiling the module because of changed source code.
[INFO] Compiling 12 source files with javac [debug parameters release 21] to target\classes
[INFO] Die Annotationsverarbeitung ist aktiviert, da mindestens ein Prozessor im
  Classpath gefunden wurde. In einem zukünftigen Release von javac kann die Annotationsverarbeitung deaktiviert werden,
  es sei denn, mindestens ein Prozessor ist namentlich angegeben (-processor), oder ein Suchpfad
  ist angegeben (--processor-path, --processor-module-path), oder die Annotationsverarbeitung
  wurde explizit aktiviert (-proc:only, -proc:full).
  Verwenden Sie "-Xlint:-options", um diese Meldung zu unterdrücken.
  Verwenden Sie "-proc:none", um die Annotationsverarbeitung zu deaktivieren.
[INFO] 
[INFO] --- resources:3.3.1:testResources (default-testResources) @ chat-service ---
[INFO] skip non existing resourceDirectory C:\Users\campi\it3c-m321\chat-service\src\test\resources
[INFO] 
[INFO] --- compiler:3.14.1:testCompile (default-testCompile) @ chat-service ---
[INFO] Recompiling the module because of changed dependency.
[INFO] Compiling 8 source files with javac [debug parameters release 21] to target\test-classes
[INFO] Die Annotationsverarbeitung ist aktiviert, da mindestens ein Prozessor im
  Classpath gefunden wurde. In einem zukünftigen Release von javac kann die Annotationsverarbeitung deaktiviert werden,
  es sei denn, mindestens ein Prozessor ist namentlich angegeben (-processor), oder ein Suchpfad
  ist angegeben (--processor-path, --processor-module-path), oder die Annotationsverarbeitung
  wurde explizit aktiviert (-proc:only, -proc:full).
  Verwenden Sie "-Xlint:-options", um diese Meldung zu unterdrücken.
  Verwenden Sie "-proc:none", um die Annotationsverarbeitung zu deaktivieren.
[INFO] 
[INFO] --- surefire:3.5.6:test (default-test) @ chat-service ---
[INFO] Using auto detected provider org.apache.maven.surefire.junitplatform.JUnitPlatformProvider
[INFO] 
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running ch.benedict.m321.chatservice.delivery.RoomChannelTest
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.264 s -- in ch.benedict.m321.chatservice.delivery.RoomChannelTest
[INFO] 
[INFO] Results:
[INFO] 
[INFO] Tests run: 5, Failures: 0, Errors: 0, Skipped: 0
[INFO] 
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  16.335 s
[INFO] Finished at: 2026-11-13T23:21:25+01:00
[INFO] ------------------------------------------------------------------------
PS C:\Users\campi\it3c-m321>



## Schritt 2 — Die Nachricht lesen

Ziel: Aus den Bytes vom Exchange wird ein DeliveryEvent. Gelesen werden nur id und
roomId, das JSON bleibt Byte für Byte gleich.

Warum hier: Das ist der Vertrag aus 1.1. Ob die Bytes unverändert bleiben, lässt sich ohne
Broker am billigsten prüfen. Wird das JSON hier versehentlich neu geschrieben, sieht man es
später am Stream nicht mehr sofort.

**KI-Auftrag im Wortlaut:**

> Lies `CLAUDE.md` und in `docs/spec-live-zustellung.md` den Abschnitt 1.1. Arbeite im Paket
> `ch.benedict.m321.chatservice.delivery`.
>
> 1. `record DeliveryIds(UUID id, UUID roomId)` mit `@JsonIgnoreProperties(ignoreUnknown = true)`.
>    Er nimmt aus dem JSON nur die zwei Felder, die der Live-Weg braucht.
> 2. `DeliveryEventParser` als Spring-`@Component`. Er bekommt den `ObjectMapper` von Spring Boot
>    über den Konstruktor (`@RequiredArgsConstructor`). Methode `DeliveryEvent parse(byte[] body)`:
>    - wandelt die Bytes mit UTF-8 in einen `String`. Genau dieser String wird später die
>      `data:`-Zeile. Er wird weder verändert noch neu serialisiert,
>    - liest mit dem `ObjectMapper` aus diesem String einen `DeliveryIds`,
>    - wirft `IllegalArgumentException` mit verständlicher Meldung, wenn das JSON kaputt ist
>      oder `id` bzw. `roomId` fehlt,
>    - gibt `new DeliveryEvent(id, roomId, json)` zurück.
>
> Die Klasse `ChatMessage` wird hier nicht verwendet, die Kopfzeile `__TypeId__` auch nicht.
> Passe ausserdem den Kommentar von `ChatMessage` an: Dort steht noch, das web-gateway bekomme
> eine eigene Kopie. Der Live-Weg liegt jetzt im chat-service selbst und liest nur `id` und
> `roomId`.
>
> Schreib dazu `DeliveryEventParserTest` (JUnit 5, ohne Spring, mit `new ObjectMapper()`):
> - das JSON aus 1.2 der Spezifikation ergibt die richtige `id` und `roomId`, und `json()` ist
>   gleich dem Eingabetext (`assertEquals` auf den ganzen String),
> - ein JSON mit anderer Feldreihenfolge, zusätzlichen Leerzeichen und einem unbekannten Feld
>   kommt Zeichen für Zeichen unverändert heraus. Das beweist, dass nicht neu serialisiert wird,
> - kaputtes JSON wirft `IllegalArgumentException`,
> - JSON ohne `roomId` wirft `IllegalArgumentException`.
>


**Test:**
```powershell
.\mvnw -pl chat-service test "-Dtest=DeliveryEventParserTest"
```
Erwartet: 4 Tests, alle grün.

**Geprüft und korrigiert:**


## Schritt 3 — Eine eigene Queue pro Instanz

Ziel: Jede Instanz legt beim Start ihre eigene Queue mit den Eigenschaften aus Abschnitt 2
an, bindet sie an chat.delivery, und jede Nachricht landet im RoomChannel ihres Raums.

Warum hier: Liste und Lesen sind geprüft. Jetzt wird beides an den Broker angeschlossen,
noch ohne HTTP. Fehlt eine Eigenschaft der Queue, zum Beispiel x-max-length, sieht man das
hier mit einem Befehl.

**KI-Auftrag im Wortlaut:**

> > Lies `CLAUDE.md`, die Globalen Vorgaben in `docs/plan-live-zustellung.md` und in `docs/spec-live-zustellung.md` die Abschnitte 2 und 3.1. Lies die
> vorhandenen Klassen `RabbitConfig`, `QueueNames` und `MessagePublisher`, bevor du etwas änderst.
>
> 1. In `RabbitConfig` eine Queue-Bean `deliveryQueue` mit genau den Eigenschaften aus der
>    Tabelle in Abschnitt 2: Classic Queue, nicht durable, exclusive, auto-delete,
>    `x-max-length` 1000, `x-overflow` `drop-head`. **Den Namen vergibt der Broker**
>    (`amq.gen-…`). Erzeuge sie deshalb als `new Queue("", false, true, true, arguments)`, nicht
>    als `AnonymousQueue` (die vergibt Spring einen Namen `spring.gen-…`) und nicht mit festem
>    Namen. Die Argumente stehen in einer `Map`, Zeile für Zeile.
> 2. Eine Binding-Bean, die `deliveryQueue` mit `BindingBuilder.bind(deliveryQueue).to(deliveryExchange)`
>    an den vorhandenen Fanout-Exchange `chat.delivery` bindet.
> 3. Klasse `RoomChannels` (`@Component`). Sie hält pro `roomId` einen `RoomChannel` in einer
>    `HashMap`. Methode `RoomChannel channelFor(UUID roomId)` ist `synchronized`, legt einen
>    fehlenden `RoomChannel` an und gibt ihn zurück. Kein `computeIfAbsent` mit Lambda, sondern
>    `get`, Prüfung auf `null`, `put`.
> 4. Klasse `DeliveryListener` (`@Component`), die `org.springframework.amqp.core.MessageListener`
>    umsetzt. `onMessage(Message message)` nimmt `message.getBody()`, ruft
>    `DeliveryEventParser.parse` auf und legt das Ereignis mit `add` in den `RoomChannel` des
>    Raums. Wirft der Parser, wird die Nachricht mit `log.warn` protokolliert und verworfen,
>    nicht weitergeworfen (1.1).
> 5. In `RabbitConfig` einen `SimpleMessageListenerContainer` als Bean, mit der
>    `ConnectionFactory` von Spring Boot, dem `DeliveryListener` und
>    **`container.setQueues(deliveryQueue)` mit dem Queue-Objekt**, nicht mit einem Namen.
>    Warum: Nur mit dem Objekt fragt der Container bei jedem Neustart des Consumers den
>    aktuellen Namen ab, den der Broker vergeben hat (das braucht Schritt 4). Kein
>    `@RabbitListener` mit `queues = "#{…}"`: Dort wird der Name nur einmal beim Start
>    gelesen. Kein Message-Converter am Container, der Listener bekommt die rohen Bytes.
>
>    Zwei Einstellungen am Container, jede mit ihrem Grund im Kommentar:
>    - **`container.setMissingQueuesFatal(false)`**: Nach einer Neuverbindung versucht der
>      Container zuerst noch den alten Namen, bevor die neue Queue angelegt ist. So beschreibt
>      es die Spring-AMQP-Dokumentation unter «broker-named queues». Ohne diese Einstellung
>      kann der Consumer dann aufhören, statt es weiter zu versuchen. Das passiert nur
>      manchmal, darum muss die Einstellung von Anfang an drin sein und nicht erst, wenn ein
>      Test rot wird.
>    - **`container.setDefaultRequeueRejected(false)`**: Wirft die Verarbeitung irgendeine
>      Ausnahme, nicht nur der Parser, wird die Nachricht verworfen und nicht in die Queue
>      zurückgestellt. Sonst stellt RabbitMQ sie erneut zu, sie steht zweimal in der Liste,
>      und die Clients bekommen sie doppelt (1.1).
> 6. Diese Kommentare stimmen danach nicht mehr. Passe sie an:
>    - `RabbitConfig` und `QueueNames` sagen, das web-gateway lese `chat.delivery`. Neu: Jede
>      chat-service-Instanz liest über ihre eigene Queue.
>    - `ChatServiceApplicationTest` sagt, die Verbindung werde erst beim ersten Senden
>      aufgebaut, nicht beim Start. Mit dem Listener-Container verbindet sich der Dienst gleich
>      beim Start. Der Kommentar muss erklären, warum der Test trotzdem ohne Broker grün ist.
>    - `MessagePublisherIntegrationTest` sagt «Wir spielen ein web-gateway». Neu: Der Test
>      bindet eine eigene Queue an den Fanout, so wie es jede chat-service-Instanz tut.
>
> `ChatServiceApplicationTest` startet ohne RabbitMQ und muss grün bleiben. Prüfe, dass der
> neue Container den Start ohne Broker weder scheitern lässt noch spürbar verzögert. Wenn doch,
> beschreib mir, was du dagegen tust, bevor du es änderst.
>
> Schreib dazu `DeliveryQueueIntegrationTest` mit Testcontainers-RabbitMQ:
> - mit `rabbitMq.execInContainer("rabbitmqctl", "list_queues", "name", "durable", "auto_delete",
>   "exclusive", "arguments")` prüfen: Es gibt genau eine Queue, die mit `amq.gen-` beginnt.
>   Sie ist `false` / `true` / `true`, und die Argumente enthalten `x-max-length` 1000 und
>   `x-overflow` `drop-head`,
> - mit `rabbitmqctl list_bindings` prüfen: Eine Zeile hat `chat.delivery` als Quelle und diese
>   Queue als Ziel,
> - eine Nachricht über `MessagePublisher.publish` senden und warten (einfache Schleife mit
>   `Thread.sleep`, höchstens 10 Sekunden), bis sie im `RoomChannel` ihres Raums steht. Danach
>   prüfen, dass `eventsAfter` mit einer unbekannten ID genau dieses Ereignis liefert.eses Ereignis liefert.

Test:
```powershell
.\mvnw -pl chat-service test "-Dtest=DeliveryQueueIntegrationTest"
.\mvnw -pl chat-service test
```
Erwartet: Der neue Test ist grün, und danach sind auch alle bisherigen Tests des
chat-service grün, vor allem ChatServiceApplicationTest ohne Broker.

**Geprüft und korrigiert:**

## Schritt 4 — Neuverbindung: die neue Queue wirklich benutzen

Ziel:Verliert die Instanz ihre Verbindung, legt sie nach dem Wiederverbinden eine neue Queue
mit neuem Namen an, bindet sie an chat.delivery und holt wieder ab.

Warum hier: In 3.5 haben wir gemessen, dass RabbitMQ eine hängende Verbindung nach etwa zwei
Minuten schliesst und sich Spring danach neu verbindet. Ob die Instanz dann auch die neue Queue
benutzt, hat noch niemand geprüft. Bevor der Stream gebaut wird, muss klar sein, dass der Weg
von RabbitMQ in die Instanz auch nach einer Neuverbindung trägt (S6, «neuer Name»).

**KI-Auftrag im Wortlaut:**

> Lies `CLAUDE.md`, die Globalen Vorgaben in `docs/plan-live-zustellung.md` und in `docs/spec-live-zustellung.md` den Abschnitt 3.5 mit der Messung.
> Prüfe zuerst, dass in `RabbitConfig` am Container `setMissingQueuesFatal(false)` gesetzt ist
> (Schritt 3). Ohne diese Einstellung kann der Test zufällig grün sein, obwohl die Neuverbindung
> manchmal scheitert. Ändere sonst am Produktionscode nur dann etwas, wenn der Test unten rot
> ist. Dann erklär mir zuerst die Ursache.
>
> Schreib `DeliveryReconnectIntegrationTest` mit Testcontainers-RabbitMQ:
> 1. Den aktuellen Namen der Queue merken (`deliveryQueue.getActualName()`). Er beginnt mit
>    `amq.gen-`.
> 2. Alle Verbindungen am Broker schliessen:
>    `rabbitMq.execInContainer("rabbitmqctl", "close_all_connections", "Test")`. Das ist
>    dasselbe, was RabbitMQ bei ausbleibenden Heartbeats tut.
> 3. Warten (einfache Schleife mit `Thread.sleep`, höchstens 15 Sekunden), bis
>    `rabbitmqctl list_queues name consumers` eine Queue `amq.gen-…` mit einem **anderen**
>    Namen als vorher und `consumers` 1 zeigt. Dann prüfen: Die alte Queue ist weg, und
>    `getActualName()` liefert den neuen Namen.
> 4. Mit `rabbitmqctl list_bindings` prüfen, dass die neue Queue an `chat.delivery` gebunden ist.
> 5. Eine Nachricht über `MessagePublisher.publish` senden. Sie muss innerhalb von 10 Sekunden
>    im `RoomChannel` ihres Raums stehen.
>


**Test:**
```powershell
.\mvnw -pl chat-service test "-Dtest=DeliveryReconnectIntegrationTest"
```
Erwartet: grün. Der Test prüft beides, was schiefgehen kann: dass eine neue Queue mit neuem
Namen entsteht und gebunden ist, und dass der Container wirklich aus ihr abholt (Punkt 5).

**Geprüft und korrigiert:**


## Schritt 5 — Der Stream, nur live

Ziel: `GET /stream?roomId=…` liefert Server-Sent Events. Jede neue Nachricht des Raums geht
als id:- und data:-Zeile an alle verbundenen Clients dieses Raums, und der Server schliesst
den Stream nie von sich aus.

**Warum hier:** Erst jetzt ist der Weg vom Broker in den RoomChannel geprüft, auch nach einer
Neuverbindung. Der Stream hängt sich daran an. Last-Event-ID kommt bewusst erst in Schritt 6,
damit zuerst der einfache Fall steht.

**KI-Auftrag im Wortlaut:**

> Lies `CLAUDE.md`, die Globalen Vorgaben in `docs/plan-live-zustellung.md` und in `docs/spec-live-zustellung.md` die Abschnitte 1.2, 3.1 und 3.2.
>
> 1. `RoomChannel` bekommt eine Liste der verbundenen Clients (`List<SseEmitter>`) und drei
>    Methoden, alle `synchronized`, also unter derselben Sperre wie `add` und `eventsAfter`:
>    - `publish(DeliveryEvent event)`: zuerst `add(event)`, dann das Ereignis an jeden Client
>      senden. Schlägt das Senden an einen Client fehl, wird dieser Client nach der Schleife
>      entfernt. Nicht während der Schleife über dieselbe Liste entfernen.
>    - `subscribe(SseEmitter emitter)`: trägt den Client ein. Er bekommt nichts aus der Liste,
>      nur noch neue Nachrichten (Fall 1 in 3.6).
>    - `unsubscribe(SseEmitter emitter)`: trägt ihn aus.
>    - `subscriberCount()`: wie viele Clients eingetragen sind. Nur für Tests und Logs.
> 2. Eine Hilfsmethode `boolean send(SseEmitter emitter, DeliveryEvent event)` sendet
>    `SseEmitter.event().id(event.id().toString()).data(event.json())`. Das JSON geht als
>    `String` ohne Media-Type hinaus, damit Spring es unverändert schreibt und nicht noch einmal
>    in Anführungszeichen setzt. Sie fängt **`IOException` und `IllegalStateException`** ab und
>    gibt dann `false` zurück, sonst `true`. Die `IllegalStateException` («already completed»)
>    kommt, wenn ein Client genau während des Sendens trennt. Würde sie aus `publish`
>    herausfliegen, wäre die Nachricht für alle anderen Clients verloren.
> 3. `DeliveryListener` ruft jetzt `publish` statt `add` auf.
> 4. `StreamController` mit
>    `@GetMapping(path = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)` und
>    `@RequestParam UUID roomId`. Er erzeugt den Emitter mit **`new SseEmitter(0L)`**: 0 heisst
>    keine Wartezeit, der Stream bleibt offen, bis der Client trennt (1.2). Ohne diese Angabe
>    schliesst Tomcat ihn nach etwa 30 Sekunden. Er meldet `onCompletion`, `onTimeout` und
>    `onError` an `unsubscribe`, ruft `subscribe` auf und gibt den Emitter zurück.
> 5. Im Testordner eine Hilfsklasse `SseTestClient`: verbindet sich mit `java.net.http.HttpClient`
>    mit `.version(HttpClient.Version.HTTP_1_1)` (sonst versucht er zuerst HTTP/2) und
>    `BodyHandlers.ofInputStream()` auf eine URL, wahlweise mit Kopfzeile `Last-Event-ID`.
>    Sie liest in einem eigenen Thread Zeile für Zeile und sammelt die Werte der `id:`- und
>    `data:`-Zeilen in Listen (Leerzeichen nach dem Doppelpunkt ignorieren, beides ist gültiges
>    SSE). `close()` schliesst den InputStream und trennt damit wirklich. Die Hilfsklasse wird in
>    Schritt 6 und 7 wiederverwendet.
>
> Schreib dazu `StreamIntegrationTest` (`@SpringBootTest(webEnvironment = RANDOM_PORT)`,
> Testcontainers-RabbitMQ, im Test `spring.mvc.async.request-timeout=2s`):
> - Client in Raum A, Nachricht per `POST /messages` in Raum A mit dem Inhalt «Grüsse aus
>   Zürich» (mit Umlaut): Der Client bekommt genau ein Ereignis. `id` ist die Nachrichten-ID,
>   und `data` ist Zeichen für Zeichen das JSON, das auf dem Exchange lag. Erst mit dem Umlaut
>   beweist das auch, dass UTF-8 bis zum Client durchgeht. Zum Vergleich in einer eigenen Test-Queue an `chat.delivery` mitlesen,
>   wie es `MessagePublisherIntegrationTest` macht,
> - ein Client in Raum B bekommt dabei nichts,
> - der Content-Type der Antwort beginnt mit `text/event-stream` (`startsWith`, weil Spring
>   noch `;charset=UTF-8` anhängen kann),
> - **der Stream bleibt offen:** Client verbinden, 3 Sekunden warten (länger als die 2 s
>   Vorgabe im Test), dann eine Nachricht senden. Sie kommt an. Das beweist, dass
>   `new SseEmitter(0L)` die Vorgabe des Servers übersteuert,
> - nach `close()` eines Clients ist er bald ausgetragen: Jede Sekunde eine Nachricht senden,
>   bis `subscriberCount()` 0 ist, höchstens 10 Sekunden. Das erste Senden nach dem Trennen
>   kann noch gelingen, weil es nur in einen Puffer geht. Darum genügt eine Nachricht nicht.

**Test:**
```powershell
.\mvnw -pl chat-service test "-Dtest=StreamIntegrationTest"
```
Erwartet: grün. Gegenprobe: `new SseEmitter(0L)` vorübergehend durch `new SseEmitter()`
ersetzen. Dann muss der Test «Stream bleibt offen» rot werden.

**Geprüft und korrigiert:**

## Schritt 6 — Nachholen mit Last-Event-ID, unter der Sperre

Ziel:Ein Client, der mit Last-Event-ID neu verbindet, bekommt genau die fehlenden
Nachrichten in Reihenfolge und danach live, ohne Doppelte, auch wenn während des Nachholens neue
Nachrichten eintreffen.

Warum hier: Das baut auf der Liste (Schritt 1) und dem Stream (Schritt 5) auf. Die Sperre
lässt sich erst prüfen, wenn beides steht. Der Test für S7 mit echtem RabbitMQ entsteht hier.

**KI-Auftrag im Wortlaut:**

> Lies `CLAUDE.md`, die Globalen Vorgaben in `docs/plan-live-zustellung.md` und in `docs/spec-live-zustellung.md` den Abschnitt 3.6 ganz, vor allem
> den Absatz «Neue Nachricht während des Nachholens».
>
> 1. `subscribe(SseEmitter emitter)` in `RoomChannel` wird zu
>    `subscribe(SseEmitter emitter, String lastEventId)`. Die Methode bleibt `synchronized` und
>    macht in **dieser Reihenfolge**:
>    1. `eventsAfter(lastEventId)` lesen,
>    2. diese Ereignisse in Reihenfolge an den Client senden,
>    3. erst dann den Client in die Liste der Empfänger eintragen.
>
>    Schlägt beim Nachliefern ein Senden fehl, wird der Client nicht eingetragen. Weil
>    `publish` dieselbe Sperre hält, kann zwischen 1 und 3 keine neue Nachricht eingefügt oder
>    verschickt werden. Schreib genau das in den Kommentar der Methode. Es ist der Grund, warum
>    es keine Duplikaterkennung braucht.
> 2. `StreamController` liest die Kopfzeile mit
>    `@RequestHeader(name = "Last-Event-ID", required = false) String lastEventId` und gibt sie
>    weiter. Als `String`, nicht als `UUID`: Eine unbekannte oder unsinnige ID ergibt laut 3.6
>    die ganze Liste, keinen Fehler 400.
>
> Schreib dazu `ReconnectIntegrationTest` (`RANDOM_PORT`, Testcontainers-RabbitMQ,
> `SseTestClient` aus Schritt 5):
> - **S7:** Client verbinden, eine Nachricht senden, ihre ID lesen, `close()`. 50 Nachrichten
>   mit Absender `sub-s7` senden und warten, bis alle 50 im `RoomChannel` stehen (statt der 10 s
>   aus dem Szenario: gewartet wird auf das Ereignis, nicht auf die Uhr). Mit dieser ID als
>   `Last-Event-ID` neu verbinden, dann eine Nachricht mit Absender `live` senden. Erwartet:
>   51 Ereignisse, 51 verschiedene IDs, die von `sub-s7` sind «Nachricht 1» bis
>   «Nachricht 50» lückenlos in Reihenfolge, die letzte ist die von `live`,
> - ohne `Last-Event-ID` bekommt ein neuer Client nichts von den alten Nachrichten,
> - mit einer unbekannten `Last-Event-ID` bekommt er die ganze Liste des Raums,
> - **Gleichzeitigkeit:** Ein zweiter Thread sendet 300 Nachrichten mit Absender `race`
>   hintereinander. Während er sendet, verbindet sich ein Client mit der ID der 50. Nachricht
>   als `Last-Event-ID`, sobald diese im `RoomChannel` steht. Wenn alle 300 gesendet sind,
>   müssen beim Client genau «Nachricht 51» bis «Nachricht 300» ankommen: lückenlos, in
>   Reihenfolge, keine doppelt. Den Test fünfmal hintereinander laufen lassen
>   (`@RepeatedTest(5)`), weil sich ein Fehler in der Sperre nur manchmal zeigt.
> - Jeder Test und jede der fünf Wiederholungen bekommt eine neue `roomId` mit
>   `UUID.randomUUID()`. Sonst liegen im fünften Lauf schon 1200 Nachrichten in der Liste.


**Test:**
```powershell
.\mvnw -pl chat-service test "-Dtest=ReconnectIntegrationTest,StreamIntegrationTest,RoomChannelTest"
```
Erwartet: alle grün, die Gleichzeitigkeit fünfmal. Gegenprobe: In subscribe vorübergehend
synchronized entfernen und zuerst eintragen, dann nachliefern. Dann sollte der
Gleichzeitigkeitstest in einem der fünf Läufe Doppelte finden. Bleibt er grün, ist er zu
schwach: mehr Nachrichten senden, bis die Gegenprobe rot wird. Danach zurückstellen.

**Geprüft und korrigiert:**


## Schritt 7 — Zwei Instanzen (S3)

Ziel: Ein Test startet zwei Instanzen des chat-service gegen denselben RabbitMQ und belegt
S3: Beide Clients in Raum A bekommen alle 400, jede genau einmal, die eines Absenders in
Reihenfolge, der Client in Raum B bekommt keine.

Warum hier: Erst jetzt ist eine einzelne Instanz vollständig. Der Fanout zeigt seinen Sinn
erst mit zwei Instanzen. Ohne diesen Test würde eine gemeinsame Queue statt einer pro Instanz
nicht auffallen.

**KI-Auftrag im Wortlaut:**

> Lies `CLAUDE.md`, die Globalen Vorgaben in `docs/plan-live-zustellung.md` und in `docs/spec-live-zustellung.md` die Abschnitte 2, 3.2 und das
> Szenario S3 in Abschnitt 4.
>
> Schreib `TwoInstancesIntegrationTest`. Kein `@SpringBootTest`. Stattdessen:
> - ein Testcontainers-RabbitMQ für die ganze Klasse,
> - zwei Instanzen mit `new SpringApplicationBuilder(ChatServiceApplication.class)`, jede mit
>   `server.port=0` und den Verbindungsdaten des Containers (`spring.rabbitmq.host`, `port`,
>   `username`, `password`). Den Port jeder Instanz aus `local.server.port` lesen. Beide nach
>   dem Test schliessen,
> - vorher warten (Schleife, höchstens 10 Sekunden), bis `rabbitmqctl list_queues name consumers`
>   genau zwei Queues `amq.gen-…` mit `consumers` 1 zeigt. Die Consumer starten im Hintergrund,
>   direkt nach dem Start ist vielleicht erst einer da,
> - Client A1 an Instanz 1, Client A2 an Instanz 2, beide Raum A. Client B an Instanz 2,
>   Raum B. Alle mit `SseTestClient`,
> - 200 Nachrichten mit Absender `sub-1` über Instanz 1 und 200 mit Absender `sub-2` über
>   Instanz 2, beide für Raum A, Inhalt «Nachricht 1» bis «Nachricht 200»,
> - warten (Schleife, höchstens 20 Sekunden), bis A1 und A2 je 400 Ereignisse haben, dann noch
>   2 Sekunden, damit zu viele auffallen würden,
> - erwartet: A1 und A2 je genau 400 Ereignisse und 400 verschiedene IDs. Pro Absender sind die
>   Nummern lückenlos 1 bis 200 in Reihenfolge. Zum Lesen von `senderId` und `content` die
>   `data`-Zeilen mit dem `ObjectMapper` lesen. B hat 0 Ereignisse.
>


**Test:**
```powershell
.\mvnw -pl chat-service test "-Dtest=TwoInstancesIntegrationTest"
```
Erwartet: grün. Gegenprobe: In RabbitConfig vorübergehend einen festen Queue-Namen statt
"" setzen und exclusive auf false, sodass sich beide Instanzen eine Queue teilen (nur mit
exclusive allein würde die zweite Instanz gar nicht starten). Dann verteilt RabbitMQ die
Nachrichten auf beide, und A1 und A2 bekommen je weniger als 400. Danach zurückstellen.

**Geprüft und korrigiert:**


## Schritt 8 — Doku und Abnahme von Hand

Ziel: README und .env.example stimmen, und die Szenarien S1 bis S8 aus Abschnitt 4 der
Spezifikation sind von Hand gelaufen.

Warum zuletzt: Doku über etwas, das noch nicht läuft, wäre geraten. S2, S4, S5 und S6
brauchen den ganzen Stack mit Gateway, batch-writer und Postgres. Das deckt kein Unit-Test ab.

**KI-Auftrag im Wortlaut:**

> Lies `CLAUDE.md`, `README.md`, `.env.example` und `docs/spec-live-zustellung.md`.
>
> 1. In `README.md`, Tabelle «Was gebaut wird», Spalte «Aufgabe» des `chat-service`: ergänzen,
>    dass er Nachrichten per Server-Sent Events (`GET /stream?roomId=…`) live an die Clients
>    eines Raums zustellt. «Stand» bleibt «vorhanden».
> 2. In `README.md`, Abschnitt «Dokumente»: `docs/spec-live-zustellung.md` und
>    `docs/plan-live-zustellung.md` mit je einem Satz eintragen, im Stil der Einträge zum
>    batch-writer.
> 3. `.env.example`: Prüfe, ob einer der Schritte 1 bis 7 eine neue Umgebungsvariable
>    eingeführt hat. Wenn ja, mit Beispielwert und deutschem Kommentar eintragen. Wenn nein,
>    nichts ändern und mir das so sagen.
>

**Test:** Die Szenarien aus Abschnitt 4 der Spezifikation von Hand, in dieser Reihenfolge:
S1, S8, S2, dann S3 bis S7. Ergebnis jedes Szenarios mit Zahl unten eintragen.

**Geprüft und korrigiert:**

---

## Abgleich mit der Spezifikation

| Festlegung | Wo in der Spec | Schritt |
|---|---|---|
| Queue-Eigenschaften, Name vom Broker | 2 | 3 |
| Nach Neuverbindung die neue Queue benutzen | 3.5, S6 | 4 |
| Nur `id` und `roomId` lesen, Bytes unverändert als `data:` | 1.1 | 2, geprüft in 5 |
| `GET /stream?roomId=…`, `id:`- und `data:`-Zeile | 1.2 | 5 |
| Stream bleibt offen, keine Wartezeit | 1.2 | 5 |
| Liste der letzten 1000 pro Raum | 3.6 | 1 |
| Ohne Last-Event-ID nur live | 3.6 | 1, 5 |
| Mit Last-Event-ID: danach; ID unbekannt: ganze Liste | 3.6 | 1, 6 |
| Sperre pro Raum: nachholen, dann eintragen, wie beim Einfügen | 3.6 | 1, 5, 6 |
| Raumfilter | 3.2 | 5, 7 |
| Toter Client wird beim fehlgeschlagenen Senden ausgetragen | 1.2 | 5 |
| Test mit echtem RabbitMQ: zwei Instanzen | S3 | 7 |
| Test mit echtem RabbitMQ: Neuverbindung | S7 | 6 |
| README «Stand», `.env.example` | — | 8 |

| Szenario | Automatisch in | Von Hand in |
|---|---|---|
| S1 alles grün | alle Schritte | 8 |
| S2 nur das Gateway hat einen Port | — | 8 |
| S3 zwei Instanzen, Raumfilter | 7 | 8 |
| S4 Queue verschwindet beim Herunterskalieren | 4 (gleicher Mechanismus) | 8 |
| S5 Datenbank weg, Live-Weg läuft | — (Live-Weg kennt keine Datenbank) | 8 |
| S6 angehaltene Instanz | 4 (Neuverbindung) | 8 |
| S7 Neuverbindung mit Last-Event-ID | 6 | 8 |
| S8 Regeln aus `CLAUDE.md` | alle Schritte | 8 |
