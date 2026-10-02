# Spezifikation — batch-writer

**Modul M321 · Klasse IT3c · Bewertung 1**
**Autor:** Flavio Campigotto · **Stand:** 02.10.2026

Der `batch-writer` holt die Nachrichten aus der Queue `chat.persist` und legt sie dauerhaft
in PostgreSQL ab. Er ist Schritt 4 der Umsetzungsreihenfolge aus `PLANUNG.md`.

Diese Spezifikation ist so geschrieben, dass jemand anders den Dienst daraus bauen kann,
ohne zu fragen. Wo eine Entscheidung mehrere Wege hatte, steht der verworfene Weg dabei.

---

## 1. Zweck und Abgrenzung

### 1.1 Was der Dienst tut

Er ist **der einzige Dienst, der in die Datenbank schreibt**. Er nimmt Nachrichten von
`chat.persist` entgegen, sammelt sie zu Stapeln und schreibt jeden Stapel mit **einem**
Datenbankbefehl.

Der Grund steht in `PLANUNG.md`, Abschnitt 4.1: das System soll 100'000 Nachrichten pro
Minute aushalten, also rund **1'667 pro Sekunde**. So viele einzelne `INSERT` verträgt keine
Datenbank auf Dauer — nicht wegen der Datenmenge, sondern wegen der Anzahl Transaktionen.
Bei einer Stapelgrösse von 500 werden daraus rund **3,3 Schreibvorgänge pro Sekunde**.

Das ist der ganze Zweck des Dienstes. Ohne diese Zahl wäre er überflüssig, und der
`chat-service` könnte selbst schreiben.

### 1.2 Was der Dienst bewusst nicht tut

| Nicht enthalten | Warum |
|---|---|
| **Lesen der Chat-Historie** | Lesepfad, gehört zum `chat-service`. Der `batch-writer` hat keine REST-Schnittstelle |
| **Räume und Mitgliedschaften** | Eigenes Thema, nicht Teil dieser Bewertung. Darum legt die Migration **nur** die Tabelle `message` an |
| **Fremdschlüssel von `message.room_id` auf `room`** | Es gibt keine Tabelle `room`. Ein Fremdschlüssel würde jeden Einfügevorgang scheitern lassen, weil der `chat-service` beliebige Raum-Kennungen vergibt, ohne dass ein Raum angelegt wurde. Siehe 4.2 |
| **Prüfung des Tokens** | Der Dienst ist von aussen nicht erreichbar und bekommt seine Nachrichten über den Broker. `PLANUNG.md` 3.1: das Gateway ist der einzige Wachposten |
| **Auslesen der Dead-Letter-Queue** | Der Dienst **füllt** `chat.dlq` (siehe 3.4), liest sie aber nicht aus. Was damit geschieht, ist noch offen |
| **Publisher Confirms, Verschlüsselung, Partitionierung** | Erst messen, dann härten |

### 1.3 Einordnung im Gesamtsystem

```
chat-service  ──publish──▶  chat.persist  ──consume──▶  batch-writer  ──Bulk-INSERT──▶  PostgreSQL
                                 │
                                 └── bei dauerhaftem Fehler ──▶  chat.dlq
```

Der `batch-writer` kennt den `chat-service` nicht und spricht nie mit ihm. Die einzige
Verbindung zwischen beiden ist das JSON auf der Queue.

---

## 2. Vertrag: was genau auf der Queue ankommt

### 2.1 Woher ich das weiss

Nicht aus dem Quelltext gelesen und gehofft, sondern **nachgemessen**. Ich habe den
Nachrichten-Konverter des `chat-service` eine Nachricht serialisieren lassen und mir Rumpf
und Kopfzeilen ausgeben lassen. Der Versuchsaufbau ist eine Wegwerf-Testklasse, die
`Jackson2JsonMessageConverter` mit demselben `ObjectMapper` aufruft, den
`chat-service/src/main/java/.../config/RabbitConfig.java` als Bean bereitstellt.

Nachprüfen lässt sich dasselbe am laufenden System:

```bash
docker compose exec rabbitmq rabbitmqadmin get queue=chat.persist count=1 ackmode=reject_requeue_true
```

### 2.2 Der Rumpf

```json
{
  "id": "0b3c2f5a-1111-4a2b-8c3d-4e5f60718293",
  "roomId": "9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061",
  "senderId": "keycloak-sub-123",
  "senderName": "Alice Muster",
  "content": "Hallo Welt",
  "sentAt": "2026-09-25T10:15:30.123456Z"
}
```

| Feld | Typ im JSON | Bedeutung | Pflicht |
|---|---|---|---|
| `id` | UUID als Zeichenkette | Vom `chat-service` vergeben. Primärschlüssel und Grundlage der Duplikat-Abwehr | ja |
| `roomId` | UUID als Zeichenkette | Der Raum. Ohne Fremdschlüssel, siehe 4.2 | ja |
| `senderId` | Zeichenkette | Die `sub`-Kennung aus Keycloak | ja |
| `senderName` | Zeichenkette | Anzeigename, denormalisiert | ja |
| `content` | Zeichenkette | Der Text der Nachricht | ja |
| `sentAt` | ISO-8601 mit Zeitzone | Server-Zeitstempel, **Mikrosekunden** | ja |

Die Feldnamen stehen in **camelCase** — nicht in snake_case wie die Spalten der Tabelle.
Die Zuordnung macht der `batch-writer`.

### 2.3 Die Kopfzeilen — und die eine, die zur Falle wird

| Eigenschaft | Wert |
|---|---|
| `content_type` | `application/json` |
| `content_encoding` | `UTF-8` |
| `delivery_mode` | `PERSISTENT` (2) |
| Kopfzeile `__TypeId__` | `ch.benedict.m321.chatservice.dto.ChatMessage` |

**`__TypeId__` ist der wichtigste Befund dieser Spezifikation.** Diese Kopfzeile setzt
Springs `Jackson2JsonMessageConverter` von sich aus. Sie nennt den **vollständigen
Java-Klassennamen im `chat-service`** — eine Klasse, die es im `batch-writer` nicht gibt
und bewusst nicht geben soll (`PLANUNG.md`, Abschnitt 5: kein gemeinsames Modul).

Daraus folgen zwei Fehler, die man sonst erst im Betrieb merkt:

1. Benutzt der `batch-writer` ebenfalls einen `Jackson2JsonMessageConverter`, versucht
   dieser die Klasse aus der Kopfzeile zu laden. Sie fehlt → `ClassNotFoundException` →
   **jede echte Nachricht landet in `chat.dlq` statt in der Datenbank.**
2. Eine Nachricht, die jemand von Hand einlegt, trägt die Kopfzeile **nicht**. Der
   Konverter weiss dann nicht, in welchen Typ er umwandeln soll.

**Entscheidung: Der `batch-writer` benutzt keinen typisierenden Konverter.** Er nimmt die
AMQP-Nachricht roh entgegen, liest `getBody()` und wandelt die Bytes selbst mit Jackson in
seine **eigene** Datenklasse um. Die Kopfzeile `__TypeId__` wird ignoriert.

Das ist genau das, was der `chat-service` in seinem eigenen Kommentar verlangt:

> *„Diese Form ist der Vertrag zwischen den Diensten — aber der Vertrag ist das JSON, nicht
> diese Klasse."*

Verworfen wurde, den Konverter über einen `ClassMapper` auf die eigene Klasse
umzubiegen. Das funktioniert, versteckt die Zuordnung aber in einer Framework-Einstellung,
die man nicht in zwei Sätzen erklärt — `CLAUDE.md` verbietet genau das.

### 2.4 Was der Dienst zusätzlich verträgt

- **Ohne `__TypeId__`**: wird verarbeitet wie jede andere Nachricht. Der Header wird nie gelesen.
- **Unbekannte Felder im JSON**: werden überlesen. So kann der `chat-service` später ein Feld
  ergänzen, ohne dass der `batch-writer` bricht.
- **Fehlende Pflichtfelder oder kaputtes JSON**: dauerhafter Fehler, siehe 3.4.

---

## 3. Verhalten

### 3.1 Normalfall

1. Der Dienst hängt als Verbraucher an `chat.persist`.
2. Er sammelt bis zu **500** Nachrichten oder bis **200 ms** vergangen sind — was zuerst
   eintritt.
3. Er wandelt jede Nachricht des Stapels in sein Datenobjekt um.
4. Er schreibt den ganzen Stapel mit **einem** `INSERT … ON CONFLICT (id) DO NOTHING`.
5. Nach dem erfolgreichen `COMMIT` bestätigt er den ganzen Stapel gegenüber RabbitMQ.

**Bestätigt wird erst nach dem COMMIT.** Das ist der Kern von At-least-once: stürzt der
Dienst zwischen Schreiben und Bestätigen ab, liefert RabbitMQ erneut. Es geht nichts
verloren, aber es können Duplikate entstehen — die 3.3 abfängt.

Stapelgrösse 500 und 200 ms sind aus `PLANUNG.md` 4.1 übernommen und über
Umgebungsvariablen änderbar (5.2).

### 3.2 Zwei Instanzen nebeneinander

Beide Instanzen hängen an **derselben** Queue `chat.persist`. RabbitMQ verteilt die
Nachrichten reihum, jede geht an **genau einen** Verbraucher. Das Muster heisst
**Competing Consumers** und braucht keinerlei Absprache zwischen den Instanzen.

Es braucht insbesondere **keine** Sperre und keine Aufteilung nach Raum: zwei Instanzen
bekommen nie dieselbe Nachricht, und selbst wenn doch, würde 3.3 greifen.

Verworfen: eine exklusive Queue pro Instanz. Das wäre Fanout und würde jede Nachricht
mehrfach schreiben — das Gegenteil des Gewünschten.

### 3.3 Dieselbe Nachricht kommt zweimal

**Verhalten:** Es entsteht **genau eine** Zeile. Die zweite Einfügung wird still verworfen.
Die Nachricht wird trotzdem bestätigt und landet **nicht** in `chat.dlq`.

**Wie:** `message.id` ist Primärschlüssel, und der Einfügebefehl endet auf
`ON CONFLICT (id) DO NOTHING`. Die Datenbank entscheidet, nicht der Dienst — ein vorheriges
`SELECT` wäre eine zweite Abfrage und bei zwei Instanzen trotzdem unsicher.

**Warum das kein Exactly-once ist:** Wir behaupten nicht, dass jede Nachricht genau einmal
verarbeitet wird. Wir stellen nur sicher, dass eine mehrfach verarbeitete Nachricht **keinen
Schaden anrichtet**. Das ist ein Unterschied, den verteilte Systeme ernst nehmen müssen.

Duplikate können aus drei Richtungen kommen: Neuzustellung nach Absturz, Neuzustellung nach
Datenbankausfall (3.4), oder ein Erzeuger, der dieselbe Nachricht zweimal sendet.

### 3.4 Fehlerfälle — die Trennlinie

Der Dienst unterscheidet **zwei Sorten Fehler**, und diese Unterscheidung ist die wichtigste
Entwurfsentscheidung nach 2.3:

| Sorte | Beispiel | Was passiert | Warum |
|---|---|---|---|
| **Vorübergehend** | Datenbank nicht erreichbar, Zeitüberschreitung, Verbindung weg | Stapel **zurück in die Queue**, nach einer Pause erneut versuchen. **Nie** in die DLQ | Ein erneuter Versuch kann gelingen. Die Nachricht ist in Ordnung, nur die Umgebung nicht |
| **Dauerhaft** | Rumpf ist kein gültiges JSON, Pflichtfeld fehlt, `id` ist keine UUID | Nachricht **nach `chat.dlq`**, nicht erneut versuchen | Ein erneuter Versuch wird nie gelingen. Ohne diese Trennung dreht sich die Nachricht endlos im Kreis und blockiert alles dahinter |

Wer beides gleich behandelt, bekommt entweder eine Endlosschleife oder verliert Nachrichten
bei einem kurzen Datenbankausfall.

#### 3.4.1 Die Datenbank ist kurz weg

1. Der Stapel wird gelesen und umgewandelt — das gelingt, die Datenbank ist dafür nicht nötig.
2. Das Schreiben scheitert mit einem Datenbankfehler.
3. Der Dienst protokolliert den Fehler **einmal pro Stapel**, nicht pro Nachricht.
4. Er wartet die eingestellte Pause ab (Vorgabe **2000 ms**).
5. Er lehnt den Stapel **mit Rückstellung in die Queue** ab.
6. RabbitMQ liefert erneut. Ab Schritt 1 von vorn.

Der Dienst **stirbt dabei nicht** und muss nicht von Hand neu gestartet werden. Sobald die
Datenbank antwortet, läuft der Stapel durch.

Die Pause ist kein Schönheitsfehler, sondern notwendig: ohne sie würde RabbitMQ den Stapel
sofort erneut zustellen, der Versuch sofort wieder scheitern, und der Dienst würde einen
Prozessorkern mit sinnlosen Versuchen belegen — während die Protokolldatei volläuft.

**Kein Datenverlust:** Weil erst nach dem `COMMIT` bestätigt wird, bleiben alle Nachrichten
in der Queue, solange die Datenbank weg ist.

#### 3.4.2 Eine Nachricht ist kaputt

Scheitert die Umwandlung, wird **nur diese eine** Nachricht abgelehnt, ohne Rückstellung.
Über die Dead-Letter-Einstellung der Queue `chat.persist` landet sie in `chat.dlq`. Der Rest
des Stapels wird normal geschrieben — eine kaputte Nachricht darf 499 gute nicht aufhalten.

#### 3.4.3 Der Broker ist weg

Spring AMQP baut die Verbindung von sich aus wieder auf. Der Dienst protokolliert und
wartet. Nichts geht verloren: was nicht bestätigt wurde, ist noch in der Queue.

---

## 4. Datenmodell

### 4.1 Die Tabelle

```sql
CREATE TABLE message (
    id          uuid        PRIMARY KEY,
    room_id     uuid        NOT NULL,
    sender_id   varchar(255) NOT NULL,
    sender_name varchar(255) NOT NULL,
    content     text        NOT NULL,
    sent_at     timestamptz NOT NULL
);

CREATE INDEX idx_message_room_sent_at ON message (room_id, sent_at DESC);
```

Die Spalten entsprechen `PLANUNG.md`, Abschnitt 3.7.

### 4.2 Begründete Entscheidungen

**`id` ist Primärschlüssel und wird nicht von der Datenbank erzeugt.** Die UUID kommt vom
`chat-service`. Nur deshalb kann der `batch-writer` ein Duplikat überhaupt erkennen — eine
von der Datenbank vergebene laufende Nummer wäre bei jeder Zustellung eine andere, und
`ON CONFLICT` hätte nichts, woran es anknüpfen könnte.

**Kein Fremdschlüssel auf `room`.** Die Tabelle `room` gibt es in dieser Ausbaustufe nicht,
und Räume sind nicht Teil dieser Aufgabe. Ein Fremdschlüssel würde jeden Einfügevorgang
scheitern lassen. `room_id` ist deshalb eine gewöhnliche Spalte. Sobald `room` dazukommt,
ist das eine neue Migration — und erst dann eine ehrliche Zusage.

**`timestamptz`, nicht `timestamp`.** Der `chat-service` schickt die Zeit mit Zeitzone
(`…Z`). `timestamp` ohne Zeitzone würde sie stillschweigend wegwerfen, und die Reihenfolge
der Nachrichten wäre von der Zeitzone des Servers abhängig.

**`content` ist `text`, nicht `varchar(n)`.** PostgreSQL speichert beides gleich. Eine
willkürliche Obergrenze würde nur irgendwann eine Nachricht abschneiden.

**Ein Index, nicht mehr.** `message(room_id, sent_at DESC)` bedient die einzige Leseabfrage
(„die letzten 50 Nachrichten eines Raums"). Jeder weitere Index würde den Schreibpfad
bremsen — und den wollen wir hier gerade schnell haben.

**Das Schema entsteht über Flyway**, nicht über `ddl-auto`. Begründung in `PLANUNG.md` 3.8:
der Dienst benutzt `JdbcTemplate` statt JPA, es gibt also gar keine Klassen, aus denen ein
Schema abgeleitet werden könnte. Ausserdem nimmt Flyway beim gleichzeitigen Start mehrerer
Instanzen eine Sperre, sodass genau eine migriert.

Die Migrationen liegen in `batch-writer/src/main/resources/db/migration/`:

| Datei | Inhalt |
|---|---|
| `V1__message.sql` | Tabelle `message` |
| `V2__index_message_room_sent_at.sql` | der Index |

Zwei Dateien statt einer, weil Tabelle und Index zwei Entscheidungen sind — in der
Versionsgeschichte lässt sich dann einzeln nachlesen, wann welche getroffen wurde.

---

## 5. Konfiguration

### 5.1 Umgebungsvariablen

Alle mit Vorgabewert, damit der Dienst auch ausserhalb von Docker startet.

| Variable | Vorgabe | Bedeutung |
|---|---|---|
| `POSTGRES_HOST` | `localhost` | Rechnername der Datenbank. Im Docker-Netz `postgres` |
| `POSTGRES_PORT` | `5432` | Port der Datenbank |
| `POSTGRES_DB` | `chat` | Name der Datenbank |
| `POSTGRES_USER` | `chat` | Benutzername |
| `POSTGRES_PASSWORD` | — | Passwort. Steht in `.env`, im Repository nur ein Beispielwert |
| `RABBITMQ_HOST` | `localhost` | Rechnername des Brokers. Im Docker-Netz `rabbitmq` |
| `RABBITMQ_USER` | `guest` | Benutzername am Broker |
| `RABBITMQ_PASSWORD` | `guest` | Passwort am Broker |
| `BATCH_SIZE` | `500` | Wie viele Nachrichten höchstens in einen Stapel gehen |
| `BATCH_TIMEOUT_MS` | `200` | Nach wie vielen Millisekunden ein angefangener Stapel trotzdem geschrieben wird |
| `DB_RETRY_PAUSE_MS` | `2000` | Wie lange nach einem Datenbankfehler gewartet wird, bevor der Stapel zurückgestellt wird |

`POSTGRES_USER`, `POSTGRES_PASSWORD` und `POSTGRES_DB` stehen mit Beispielwerten in
`.env.example`. Die echte `.env` steht in `.gitignore` und liegt nie im Repository.

### 5.2 Im Stack

Der Dienst veröffentlicht **keinen Port**. Er hat keine Schnittstelle, die jemand aufrufen
könnte — seine einzige Eingabe ist die Queue.

---

## 6. Abnahmekriterien

Jedes Kriterium mit dem Befehl, der es misst. Alle Befehle setzen voraus, dass der Stack
läuft (`docker compose up -d --build`).

| # | Kriterium | Befehl | Erwartet |
|---|---|---|---|
| A1 | Alle Tests laufen in einem Durchgang grün | `./mvnw clean test` | `BUILD SUCCESS` |
| A2 | Kein Dienst veröffentlicht einen Port | `docker compose config \| grep -c 'published:'` | `0`. Ohne Profil blendet `config` die Dienste des Profils `login` aus — gemessen wird also genau der Stack, der ohne Profil startet |
| A3 | Alle Dienste laufen | `docker compose ps --format '{{.Service}} {{.State}}'` | jede Zeile `running` |
| A4 | 1000 Nachrichten kommen an | 1000 × `POST /messages`, dann `docker compose exec postgres psql -U chat -d chat -c "SELECT count(*) FROM message;"` | `1000` innerhalb 60 s |
| A5 | Die Queue ist danach leer | `docker compose exec rabbitmq rabbitmqctl list_queues name messages` | `chat.persist 0` |
| A6 | Wenige Transaktionen | `SELECT xact_commit FROM pg_stat_database WHERE datname='chat';` vor und nach 1000 Nachrichten | Differenz ≤ 100 |
| A7 | Duplikat erzeugt eine Zeile | dieselbe Nachricht zweimal in `chat.persist` legen, dann `SELECT count(*) FROM message WHERE id='…';` | `1` |
| A8 | Duplikat landet nicht in der DLQ | `docker compose exec rabbitmq rabbitmqctl list_queues name messages` | `chat.dlq 0` |
| A9 | Zwei Instanzen hängen an der Queue | `docker compose up -d --scale batch-writer=2`, dann `rabbitmqctl list_queues name consumers` | `chat.persist 2` |
| A10 | Datenbankausfall übersteht der Dienst | `docker compose stop postgres`, 300 Nachrichten, nach 15 s `docker compose start postgres` | nach ≤ 90 s `count(*) = 300`, und `docker compose ps batch-writer` zeigt weiterhin `running` |
| A11 | Keine Streams im Quelltext | `grep -rn "\.stream()\|Collectors\." batch-writer/src/main/java` | keine Treffer |
| A12 | Keine Geheimnisse im Repository | `git ls-files \| grep -x ".env"` | keine Treffer |

---

## 7. Offene Punkte

| # | Punkt | Stand |
|---|---|---|
| 1 | Wer liest `chat.dlq` aus? | Der Dienst füllt sie, leert sie aber nie. Für den Unterricht reicht `rabbitmqctl`, auf Dauer braucht es einen Weg zurück |
| 2 | Die Datenbank wächst um ~1,2 GB pro Stunde | `PLANUNG.md`, offener Punkt 2. In dieser Ausbaustufe nicht gelöst |
| 3 | Sind 1'667 Nachrichten pro Sekunde wirklich erreichbar? | Unbewiesen. Messbar erst mit dem `load-generator` aus Schritt 5 |
| 4 | Reihenfolge innerhalb eines Raums | Bei zwei Instanzen nicht garantiert. `PLANUNG.md`, offener Punkt 3: der Client sortiert nach `sent_at` |
