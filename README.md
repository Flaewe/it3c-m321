# M321 — Chat-App (Klasse IT3c)

Lernprojekt zum Modul **M321 Verteilte Systeme / Microservices**. Wir bauen gemeinsam eine
Chat-Anwendung aus mehreren Services, die über eine Message Queue miteinander reden und mit
docker-compose gestartet werden.

## Für Lernende: so startest du

1. Dieses Repository **forken** (Button «Fork» oben rechts).
2. Deinen Fork klonen:
   ```bash
   git clone https://github.com/<dein-benutzername>/it3c-m321.git
   cd it3c-m321
   ```
3. Voraussetzungen installieren: **Java 21**, **Docker Desktop**, **Git**.
   Maven brauchst du **nicht** zu installieren — im Projekt liegt der Maven-Wrapper
   (`mvnw`), der sich beim ersten Aufruf die richtige Version selbst holt.
4. Lokale Umgebungsdatei anlegen und die Werte anpassen:
   ```bash
   cp .env.example .env
   ```
5. Die Planung lesen (siehe unten) — erst verstehen, dann programmieren.

Alle Aufgaben werden in **deinem Fork** gelöst. Das Original-Repository bleibt die Referenz.

## Bauen, testen, starten

Alle Befehle laufen im **Wurzelverzeichnis** des Projekts — dort, wo `pom.xml` liegt.

Windows (PowerShell):

```powershell
.\mvnw test                       # alle Tests, RabbitMQ kommt per Testcontainers
docker compose up --build         # das ganze Netz chat-net hochfahren
```

macOS und Linux:

```bash
./mvnw test
docker compose up --build
```

> **`mvn` statt `.\mvnw` funktioniert nicht?** Dann hast du kein eigenes Maven im PATH —
> das ist in Ordnung, genau dafür gibt es den Wrapper. Benutze immer `.\mvnw`
> beziehungsweise `./mvnw`.
>
> **`javac` auf eine einzelne Datei funktioniert nie.** Dabei fehlen dem Compiler alle
> Abhängigkeiten *und* die übrigen Klassen des Projekts — du bekommst dann rund zwanzig
> Fehler, darunter auch `Package ch.benedict.m321.chatservice.dto ist nicht vorhanden`.
> Genau dieses eigene Paket in der Liste ist das Erkennungszeichen: gebaut wird immer
> das Projekt, nie eine Datei.

Danach laufen sechs Dienste: RabbitMQ, `chat-service`, PostgreSQL, `batch-writer`,
Keycloak und `web-gateway`. **Nur das `web-gateway` ist von aussen erreichbar**, unter
<http://localhost:8080>. Alle anderen Container veröffentlichen keinen Port und sprechen nur
im internen Netz `chat-net` miteinander.

Nachprüfen:

```bash
docker compose config | grep -c 'published:'    # muss 1 ergeben
docker compose ps                                # nur web-gateway zeigt 0.0.0.0:8080->8080/tcp
```

Angaben wie `5432/tcp` in der Spalte `PORTS` sind **keine** offenen Ports: sie zeigen nur, auf
welchem Port das Programm im Container lauscht. Ein offener Port sieht so aus:
`0.0.0.0:8080->8080/tcp`.

**Ohne Docker** laufen die Tests, die keinen Container brauchen:

```powershell
.\mvnw test "-Dtest=!*IntegrationTest"      # PowerShell: Anführungszeichen nötig
```

```bash
./mvnw test -Dtest='!*IntegrationTest'      # macOS und Linux
```

Die Tests des `web-gateway` gehören dazu: sie starten ihre eigene Keycloak-Attrappe aus der
Java-Standardbibliothek statt eines echten Containers.

## Was gebaut wird

| Baustein | Technologie | Aufgabe | Stand |
|---|---|---|---|
| chat-service | Spring Boot 3, Java 21 | Nimmt Nachrichten per `POST /messages` an, legt sie auf Queue und Fanout-Exchange | vorhanden |
| rabbitmq | RabbitMQ 3.13 | Message Queue zwischen den Services | vorhanden |
| batch-writer | Spring Boot 3, Java 21 | Einziger Schreiber in die Datenbank, schreibt in Stapeln | vorhanden |
| postgres | PostgreSQL 16 | Speichert den Chat-Verlauf, Schema über Flyway | vorhanden |
| keycloak | Keycloak 26 | Login (OIDC), Realm `chat` wird beim Start importiert | vorhanden |
| web-gateway | Spring Boot 3, Java 21 | Einziger offener Port; prüft Tokens und reicht Keycloak durch | vorhanden |
| Web-UI | React | Browser-Client | folgt |

Alles unterhalb des Gateways läuft in einem internen Docker-Netzwerk und ist von aussen nicht
erreichbar.

## Dokumente

- [`PLANUNG.md`](PLANUNG.md) — Auftrag, Stack, Architektur, Nachrichtenfluss, Queues, Datenmodell,
  Umsetzungsreihenfolge. Das ist die Grundlage für alles Weitere.
- [`docs/design/2026-08-28-chat-app-planung.html`](docs/design/2026-08-28-chat-app-planung.html)
  — grafische Fassung der Planung, lokal im Browser öffnen.
- [`docs/plan-chat-service.md`](docs/plan-chat-service.md) — Schritt-für-Schritt-Plan, nach dem
  der `chat-service` gebaut wurde. Jeder Schritt mit Test.
- [`docs/spec-batch-writer.md`](docs/spec-batch-writer.md) — Spezifikation des `batch-writer`:
  der Vertrag auf der Queue, das Verhalten in jedem Fehlerfall, die Abnahmekriterien.
- [`docs/plan-batch-writer.md`](docs/plan-batch-writer.md) — der Umsetzungsplan dazu, sechs
  Schritte mit je einem Test.
- [`keycloak/README.md`](keycloak/README.md) — was im Realm steht und warum jede Einstellung
  darin so gewählt ist.
- [`CLAUDE.md`](CLAUDE.md) — Codestil-Regeln für dieses Projekt. Gelten auch für dich.
- [`docs/flipchart-chat-app.png`](docs/flipchart-chat-app.png) — das Flipchart aus der Lektion,
  von dem die Planung ausgeht.

## Codestil, kurz

Der Massstab ist: **kann eine lernende Person jede Zeile vorlesen und sagen, was sie tut?**

- Eine Anweisung pro Zeile, Zwischenresultate in benannte Variablen.
- `for`-Schleife statt Stream, `if` statt verschachteltem Ternary.
- Sprechende Namen in ganzen Wörtern.
- Über jeder Methode ein bis zwei Sätze: was sie tut und warum es sie gibt.
- Kommentare auf Deutsch, als Erklärung an eine Mitlernende.

Die vollständigen Regeln stehen in [`CLAUDE.md`](CLAUDE.md).
