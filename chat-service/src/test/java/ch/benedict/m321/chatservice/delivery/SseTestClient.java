package ch.benedict.m321.chatservice.delivery;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Ein kleiner SSE-Client für die Tests, so wie ihn auch ein Browser hätte.
 *
 * Er verbindet sich auf /stream, liest in einem eigenen Thread Zeile für
 * Zeile mit und sammelt die Werte der id:- und data:-Zeilen. Der Test kann
 * währenddessen Nachrichten senden und danach nachsehen, was angekommen ist.
 *
 * Bewusst von Hand mit java.net.http statt mit einer Bibliothek: So sieht
 * man beim Lesen, dass SSE nur Textzeilen über eine offene HTTP-Verbindung
 * sind.
 */
class SseTestClient implements AutoCloseable {

    /** Ein eigener HttpClient pro Verbindung, damit close() wirklich trennt. */
    private final HttpClient httpClient;

    private final Thread readerThread;

    private final List<String> ids = new ArrayList<>();

    private final List<String> data = new ArrayList<>();

    private String contentType;

    private InputStream body;

    private boolean closed;

    /**
     * Öffnet die Verbindung und beginnt mitzulesen.
     *
     * lastEventId darf null sein. Dann geht keine Kopfzeile Last-Event-ID
     * mit, wie bei einem Client, der sich zum ersten Mal verbindet.
     */
    SseTestClient(String url, String lastEventId) {
        // HTTP/1.1 ausdrücklich: Sonst versucht der Client zuerst HTTP/2.
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build();

        URI uri = URI.create(url);
        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(uri)
                .header("Accept", "text/event-stream");
        if (lastEventId != null) {
            requestBuilder.header("Last-Event-ID", lastEventId);
        }
        HttpRequest request = requestBuilder.build();

        this.readerThread = new Thread(() -> readStream(request), "sse-test-client");
        this.readerThread.setDaemon(true);
        this.readerThread.start();
    }

    /** Die Werte aller id:-Zeilen bisher, in der Reihenfolge des Eintreffens. */
    synchronized List<String> ids() {
        return new ArrayList<>(ids);
    }

    /** Die Werte aller data:-Zeilen bisher, in der Reihenfolge des Eintreffens. */
    synchronized List<String> data() {
        return new ArrayList<>(data);
    }

    /** Der Content-Type der Antwort, oder null, solange noch keine Antwort da ist. */
    synchronized String contentType() {
        return contentType;
    }

    /**
     * Wartet, bis mindestens so viele Ereignisse da sind, höchstens
     * maxWaitMillis. Gibt danach einfach zurück. Ob es gereicht hat, prüft
     * der Test selbst mit ids().
     */
    void waitForEvents(int expectedCount, long maxWaitMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + maxWaitMillis;
        List<String> current = ids();
        while (current.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            current = ids();
        }
    }

    /**
     * Trennt die Verbindung wirklich: Der InputStream wird geschlossen und
     * der HttpClient sofort beendet. Für den Server sieht das aus wie ein
     * Browser-Tab, der geschlossen wird.
     */
    @Override
    public void close() throws IOException {
        InputStream bodyToClose;
        synchronized (this) {
            closed = true;
            bodyToClose = body;
        }
        if (bodyToClose != null) {
            bodyToClose.close();
        }
        httpClient.shutdownNow();
    }

    /**
     * Läuft im eigenen Thread: Verbindung aufbauen, dann Zeile für Zeile
     * lesen, bis der Server oder close() die Verbindung beendet.
     */
    private void readStream(HttpRequest request) {
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            InputStream responseBody = response.body();
            rememberResponse(response, responseBody);

            InputStreamReader streamReader = new InputStreamReader(responseBody, StandardCharsets.UTF_8);
            BufferedReader reader = new BufferedReader(streamReader);
            String line = reader.readLine();
            while (line != null) {
                handleLine(line);
                line = reader.readLine();
            }
        } catch (IOException exception) {
            // Die Verbindung ist zu, durch close() oder durch den Server.
            // Für den Test ist das kein Fehler: Er prüft, was angekommen ist.
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Merkt sich Content-Type und Body. Wurde close() schon aufgerufen,
     * bevor die Antwort da war, schliessen wir den Body gleich selbst.
     */
    private void rememberResponse(HttpResponse<InputStream> response, InputStream responseBody) throws IOException {
        String headerValue = response.headers().firstValue("Content-Type").orElse(null);
        boolean alreadyClosed;
        synchronized (this) {
            contentType = headerValue;
            body = responseBody;
            alreadyClosed = closed;
        }
        if (alreadyClosed) {
            responseBody.close();
        }
    }

    /**
     * Wertet eine Zeile aus. Nach "id:" und "data:" darf ein Leerzeichen
     * stehen oder nicht, beides ist gültiges SSE. Spring schreibt es ohne
     * Leerzeichen.
     */
    private void handleLine(String line) {
        if (line.startsWith("id:")) {
            String value = valueAfterColon(line, "id:");
            synchronized (this) {
                ids.add(value);
            }
        } else if (line.startsWith("data:")) {
            String value = valueAfterColon(line, "data:");
            synchronized (this) {
                data.add(value);
            }
        }
    }

    /** Der Text nach dem Feldnamen, ohne ein einzelnes führendes Leerzeichen. */
    private String valueAfterColon(String line, String field) {
        String value = line.substring(field.length());
        if (value.startsWith(" ")) {
            value = value.substring(1);
        }
        return value;
    }
}
