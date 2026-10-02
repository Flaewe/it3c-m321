# Umsetzungsplan — batch-writer

**Spezifikation:** [`spec-batch-writer.md`](spec-batch-writer.md)
**Vorbild für die Form:** [`plan-chat-service.md`](plan-chat-service.md)

Jeder Schritt ist klein genug, dass die Klasse ihn in einer Lektion mitlesen kann, hat
einen Test, der ihn belegt, und wird ein Commit.

## Globale Vorgaben

Gelten für **jeden** Schritt:

- **Java 21**, Spring Boot **3.5.16** — dieselben Versionen wie im `chat-service`.
- **Code auf Englisch**, alles andere auf Deutsch. Kommentar über jeder Klasse und jeder
  Methode, der erklärt *warum* sie existiert.
- **Keine Streams, keine verschachtelten Aufrufe.** Ein Ergebnis pro Zeile in eine benannte
  Variable. Gilt auch in Tests.
- **Lombok** nur für `@Slf4j` und `@RequiredArgsConstructor`. Datenklassen sind `record`.
- **Keine Vorrats-Abstraktionen.** Kein Interface mit einer einzigen Implementierung.
- **Kein Port-Mapping** für den `batch-writer` in `docker-compose.yml`.
- **Keine Geheimnisse im Repository.** Neue Variablen kommen mit Beispielwert in `.env.example`.
- Jeder Commit endet mit der Zeile `Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>`.

## Warum diese Reihenfolge

Die Schritte sind so sortiert, dass **nach jedem Schritt etwas läuft und geprüft werden
kann**. Zuerst entsteht die Datenbank, weil ohne sie kein Test etwas zeigen kann. Dann das
Lesen der Queue, weil der Vertrag aus Abschnitt 2 der Spezifikation die riskanteste Annahme
des ganzen Dienstes ist — wenn der nicht stimmt, ist alles Weitere umsonst. Erst danach
kommen Stapel und Fehlerbehandlung, also die Teile, die auf beidem aufbauen.

Umgekehrt wäre es verlockend, mit dem Stapel-Schreiben anzufangen, weil das der Kern ist.
Dann hätte man aber lange nichts, was man starten kann.

---

## Schritt 1 — Modul und Datenbank

**Ziel:** Es gibt ein Maven-Modul `batch-writer`, die Datenbank läuft im Stack, und Flyway
legt die Tabelle an.

- [ ] `batch-writer/pom.xml` mit `spring-boot-starter-amqp`, `spring-boot-starter-jdbc`,
      `postgresql`, `flyway-core`, `flyway-database-postgresql`, Lombok, Testabhängigkeiten.
- [ ] Modul ins Eltern-POM eintragen.
- [ ] `BatchWriterApplication` als Startpunkt.
- [ ] `V1__message.sql` und `V2__index_message_room_sent_at.sql` nach Spezifikation 4.1.
- [ ] Dienst `postgres` in `docker-compose.yml`, mit Healthcheck, ohne Port.
- [ ] `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` in `.env.example`.

**Test:** `SchemaMigrationTest` startet Postgres per Testcontainers, lässt Flyway laufen und
prüft, dass die Tabelle `message` mit allen sechs Spalten und der Index existieren.

**Warum zuerst:** Ohne Tabelle kann kein weiterer Test etwas beweisen.

---

## Schritt 2 — Die Nachricht lesen

**Ziel:** Aus den Bytes auf der Queue wird ein Java-Objekt — und zwar ohne die Kopfzeile
`__TypeId__` zu beachten.

- [ ] `record IncomingMessage(UUID id, UUID roomId, String senderId, String senderName,
      String content, Instant sentAt)` — die **eigene** Datenklasse, keine geteilte.
- [ ] `MessageParser`, der `byte[]` mit Jackson in `IncomingMessage` umwandelt.
- [ ] Unbekannte Felder werden überlesen, fehlende Pflichtfelder sind ein Fehler.

**Test:** `MessageParserTest` ohne Broker und ohne Datenbank, mit dem JSON aus Spezifikation
2.2 als Zeichenkette:
- der Normalfall ergibt alle sechs Felder richtig, inklusive Mikrosekunden in `sentAt`,
- ein unbekanntes Feld stört nicht,
- kaputtes JSON wirft,
- ein fehlendes Pflichtfeld wirft.

**Warum hier:** Das ist die riskanteste Annahme der Spezifikation und zugleich die, die sich
am billigsten prüfen lässt — ganz ohne Container.

---

## Schritt 3 — Einen Stapel schreiben

**Ziel:** Eine Liste von Nachrichten wird mit **einem** Datenbankbefehl geschrieben, und ein
Duplikat erzeugt keine zweite Zeile.

- [ ] `MessageRepository` mit `JdbcTemplate.batchUpdate` und
      `INSERT … ON CONFLICT (id) DO NOTHING`.
- [ ] Kein JPA, kein `save()` pro Nachricht.

**Test:** `MessageRepositoryIntegrationTest` mit echtem Postgres:
- 500 Nachrichten in einem Aufruf ergeben 500 Zeilen,
- **dieselbe Nachricht zweimal ergibt eine Zeile** (Szenario S5),
- die Zahl der Transaktionen steigt dabei um weniger als die Zahl der Nachrichten
  (gemessen über `pg_stat_database.xact_commit`).

**Warum hier:** Jetzt ist der ganze Weg von den Bytes bis in die Tabelle beisammen — nur
noch nicht an die Queue angeschlossen.

---

## Schritt 4 — An die Queue hängen

**Ziel:** Der Dienst holt Nachrichten selbstständig von `chat.persist` und schreibt sie.

- [ ] `RabbitConfig`: Queue `chat.persist` und `chat.dlq` wie im `chat-service` deklarieren,
      damit der Dienst auch allein starten kann.
- [ ] Listener-Fabrik mit Stapelbetrieb: `BATCH_SIZE`, `BATCH_TIMEOUT_MS`, Bestätigung
      des ganzen Stapels erst nach dem Schreiben.
- [ ] `MessageBatchListener`, der den Stapel entgegennimmt, umwandelt und schreibt.

**Test:** `MessageBatchListenerIntegrationTest` mit echtem RabbitMQ und echtem Postgres:
- 1000 eingelegte Nachrichten stehen danach alle in der Tabelle,
- die Queue ist danach leer,
- eine Nachricht **ohne** `__TypeId__` wird genauso verarbeitet (Szenario S5).

**Warum hier:** Erst jetzt, wo Lesen und Schreiben einzeln bewiesen sind, lohnt sich der
Aufwand mit zwei Containern im Test.

---

## Schritt 5 — Fehler unterscheiden

**Ziel:** Vorübergehende Fehler führen zurück in die Queue, dauerhafte in die DLQ.

- [ ] Kaputte Nachricht → `AmqpRejectAndDontRequeueException` → `chat.dlq`. Der Rest des
      Stapels wird trotzdem geschrieben.
- [ ] Datenbankfehler → einmal protokollieren, `DB_RETRY_PAUSE_MS` warten, Stapel
      zurückstellen. Der Dienst stirbt nicht.

**Test:** `FailureHandlingIntegrationTest`:
- **Datenbank weg** (Container angehalten), Nachrichten eingelegt, Datenbank zurück →
  alle Nachrichten landen in der Tabelle, der Dienst läuft ohne Neustart weiter
  (Szenario S7),
- eine Nachricht mit kaputtem Rumpf landet in `chat.dlq`, die gültigen daneben in der
  Tabelle.

**Warum zuletzt:** Fehlerbehandlung lässt sich erst prüfen, wenn der Normalfall steht.

---

## Schritt 6 — In den Stack

**Ziel:** `docker compose up -d --build` startet den Dienst mit.

- [ ] `batch-writer/Dockerfile` nach dem Vorbild des `chat-service`, gebaut mit
      `-f batch-writer/pom.xml`.
- [ ] Dienst in `docker-compose.yml`, abhängig von `postgres` und `rabbitmq` mit
      `condition: service_healthy`, ohne Port.
- [ ] README: Tabelle «Stand» nachführen, Befehle ergänzen.

**Test:** `./mvnw clean test` ist in einem Lauf grün (Szenario S1), und der Prüfbefehl für
die Port-Vorgabe ergibt weiterhin `0` (Szenario S2).

**Warum zuletzt:** Ein Dockerfile für etwas zu schreiben, das noch nicht läuft, ist Arbeit
auf Verdacht.

---

## Abgleich mit den Szenarien

| Szenario | Wird abgedeckt in |
|---|---|
| S1 alles grün | Schritt 6 |
| S2 kein offener Port | Schritt 1 und 6 |
| S3 1000 Nachrichten | Schritt 4 |
| S4 höchstens 100 Transaktionen | Schritt 3 |
| S5 Duplikat, ohne `__TypeId__` | Schritt 2, 3 und 4 |
| S6 zwei Instanzen | Schritt 4 (Competing Consumers, ohne eigenen Code) |
| S7 Datenbank weg | Schritt 5 |
| S8 Regeln aus `CLAUDE.md` | alle Schritte |

---

## Abweichung vom Plan

Schritt 4 und Schritt 5 sind **in einem Commit** umgesetzt und nicht in zweien.

Der Grund: die Fehlerbehandlung steckt in derselben Methode wie das Entgegennehmen des
Stapels. Ein Commit für Schritt 4 allein hätte einen Listener enthalten, der bei einem
Datenbankfehler den ganzen Stapel in die Dead-Letter-Queue schiebt — also genau das
Verhalten, das Schritt 5 verhindern soll. Ein Zwischenstand, von dem man weiss, dass er
falsch ist, gehört nicht in die Versionsgeschichte.

Die Tests sind trotzdem getrennt geblieben: `MessageBatchListenerIntegrationTest` für
Schritt 4, `DatabaseOutageTest` für Schritt 5.
