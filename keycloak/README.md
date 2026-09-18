# Keycloak-Realm `chat`

Der Realm wird beim Start des Containers aus `realm-chat.json` importiert
(`start-dev --import-realm`). Es wird **nichts von Hand geklickt** — damit ist das
Setup reproduzierbar, und ein `docker compose down -v` kostet keine Arbeit.

## Was drin ist

| Element | Wert | Warum |
|---|---|---|
| Realm | `chat` | Der Name taucht im Token als Teil des `iss` auf: `http://localhost:8080/auth/realms/chat` |
| Client | `chat-web` | Die React-App im Browser |
| Rollen | `user`, `admin` | Vorschlag aus PLANUNG.md, offener Punkt 6. Nur `admin` soll später die Queue-Tiefe sehen |
| Benutzer | `alice`, `bob`, `admin-user` | Zwei normale Konten, damit man einen Chat zu zweit vorführen kann, und eines mit `admin` |

**Passwort aller drei Konten: `schulung`.** Das ist ein Schulungs-Beispielwert und
steht bewusst im Repository — `CLAUDE.md` erlaubt genau das. Echte Geheimnisse
(das Keycloak-Admin-Passwort) stehen in `.env` und nicht hier.

## Drei Einstellungen, die eine Begründung haben

**`"publicClient": true`** — eine Single-Page-App liefert ihren gesamten Code an den
Browser aus. Ein Client-Secret darin wäre öffentlich lesbar und damit keines. Deshalb
Authorization Code Flow **mit PKCE** statt mit Secret.

**`"pkce.code.challenge.method": "S256"`** — PKCE ersetzt das fehlende Secret: der Client
schickt beim Login einen Hash eines Zufallswerts mit und beim Einlösen des Codes den
Zufallswert selbst. Wer den Code unterwegs abfängt, kann ihn ohne diesen Wert nicht
einlösen.

**`"directAccessGrantsEnabled": false`** — das schaltet den
Resource-Owner-Password-Grant ab. Den hatten wir in der Planung verworfen (PLANUNG.md,
Abschnitt 3.2), weil unsere Anwendung dabei das Passwort des Benutzers entgegennehmen
müsste. Hier ist die Ablehnung nicht nur aufgeschrieben, sondern durchgesetzt: Keycloak
würde so eine Anfrage zurückweisen.

**`"sslRequired": "none"`** — lokal läuft alles über HTTP. Ohne diese Zeile verlangt
Keycloak HTTPS und der Login schlägt fehl. Für ein Unterrichtsprojekt auf dem eigenen
Rechner ist das in Ordnung; TLS ist offener Punkt 16.

## Was noch fehlt

Der Client `chat-desktop` für den JavaFX-Client ist **nicht** drin. Er wird erst in
Schritt 7 gebraucht, und seine Redirect-URI (Loopback mit wechselndem Port nach
RFC 8252) will an einem laufenden Keycloak ausprobiert werden, statt geraten zu werden.
Siehe PLANUNG.md, offene Punkte 4 und 17.

## Ändern im Unterricht

Die Admin-Oberfläche ist von aussen nicht erreichbar — das ist so gewollt (offener
Punkt 5). Wer etwas ausprobieren will:

```bash
# Realm-Datei anpassen, dann Keycloak neu aufsetzen
docker compose down -v
docker compose up -d keycloak
```

Das `-v` wirft die Daten weg, sonst importiert Keycloak den Realm nicht erneut.
