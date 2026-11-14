package ch.benedict.m321.chatservice.delivery;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Ein einzelner Raum in dieser Instanz: sein Gedächtnis und seine Clients.
 *
 * Ein Client, der kurz die Verbindung verliert, soll die verpassten
 * Nachrichten nachholen können, ohne dass wir die Datenbank fragen
 * (Spezifikation 3.6). Dafür merkt sich der Raum seine letzten
 * {@value #MAX_EVENTS} Ereignisse in der Reihenfolge, in der sie
 * angekommen sind.
 *
 * Ausserdem kennt der Raum die Clients, die gerade per SSE verbunden sind,
 * und schickt ihnen jede neue Nachricht. So erfüllt er den Raumfilter aus
 * 3.2: Eine Nachricht geht nur an die Clients ihres eigenen Raums.
 *
 * Alle Methoden sind synchronized, also unter EINER Sperre pro Raum. Das ist
 * die Sperre aus 3.6: Solange eine Nachricht eingefügt und verschickt wird,
 * kann sich kein Client dazwischen eintragen, und umgekehrt.
 *
 * Die Liste lebt nur im Speicher. Startet die Instanz neu, ist sie leer.
 */
@Slf4j
public class RoomChannel {

    /**
     * So viele Ereignisse hält ein Raum höchstens. Dieselbe Grenze wie
     * x-max-length der Queue: Mehr als 1000 verpasste Nachrichten holt
     * man auf dem Live-Weg ohnehin nicht nach.
     */
    static final int MAX_EVENTS = 1000;

    /** Die Ereignisse, das älteste vorne, das neueste hinten. */
    private final List<DeliveryEvent> events = new ArrayList<>();

    /** Die Clients, die gerade an diesem Raum hängen. */
    private final List<SseEmitter> subscribers = new ArrayList<>();

    /**
     * Eine neue Nachricht aus der Queue: merken und an alle Clients des
     * Raums schicken.
     *
     * Beides passiert unter derselben Sperre. Ein Client, der sich gerade
     * verbindet, sieht die Nachricht deshalb entweder schon in der Liste
     * oder bekommt sie live, nie beides und nie keins (3.6).
     *
     * Schlägt das Senden an einen Client fehl, ist er weg (1.2). Wir merken
     * ihn uns und tragen ihn erst nach der Schleife aus. Entfernen während
     * der Schleife über dieselbe Liste würde Java mit einer
     * ConcurrentModificationException beantworten.
     */
    public synchronized void publish(DeliveryEvent event) {
        add(event);

        List<SseEmitter> failed = new ArrayList<>();
        for (SseEmitter emitter : subscribers) {
            boolean sent = send(emitter, event);
            if (!sent) {
                failed.add(emitter);
            }
        }

        subscribers.removeAll(failed);
        if (!failed.isEmpty()) {
            log.debug("Removed {} disconnected client(s), {} left", failed.size(), subscribers.size());
        }
    }

    /**
     * Trägt einen Client ein und liefert ihm vorher nach, was er verpasst
     * hat (Spezifikation 3.6).
     *
     * Was er aus der Liste bekommt, entscheidet eventsAfter: ohne
     * Last-Event-ID nichts, mit bekannter ID alles danach, mit unbekannter
     * ID die ganze Liste.
     *
     * Die Reihenfolge ist der Kern dieser Methode:
     * 1. die verpassten Ereignisse aus der Liste lesen,
     * 2. sie in Reihenfolge an den Client senden,
     * 3. erst dann den Client als Empfänger eintragen.
     *
     * Warum das ohne Duplikaterkennung auskommt: publish hält dieselbe
     * Sperre wie diese Methode. Zwischen 1 und 3 kann darum keine neue
     * Nachricht eingefügt oder verschickt werden. Eine neue Nachricht kommt
     * entweder vorher in die Liste, dann ist sie unter den nachgelieferten,
     * oder nachher, dann geht sie live an den schon eingetragenen Client.
     * Nie beides und nie keins. Und weil das Nachliefern fertig ist, bevor
     * die erste Live-Nachricht hinausgeht, bleibt auch die Reihenfolge.
     *
     * Schlägt beim Nachliefern ein Senden fehl, ist der Client schon wieder
     * weg. Dann tragen wir ihn gar nicht erst ein.
     */
    public synchronized void subscribe(SseEmitter emitter, String lastEventId) {
        List<DeliveryEvent> missedEvents = eventsAfter(lastEventId);

        for (DeliveryEvent event : missedEvents) {
            boolean sent = send(emitter, event);
            if (!sent) {
                log.debug("Client disconnected while catching up, not subscribing it");
                return;
            }
        }

        subscribers.add(emitter);
        log.debug("Caught up {} event(s), {} client(s) subscribed", missedEvents.size(), subscribers.size());
    }

    /**
     * Trägt einen Client aus. Spring ruft das auf, wenn die Verbindung
     * endet. Ist er schon weg, weil das Senden fehlschlug, passiert nichts.
     */
    public synchronized void unsubscribe(SseEmitter emitter) {
        subscribers.remove(emitter);
    }

    /** Wie viele Clients gerade eingetragen sind. Für Tests und Logs. */
    public synchronized int subscriberCount() {
        return subscribers.size();
    }

    /**
     * Schickt ein Ereignis als id:- und data:-Zeile an einen Client.
     *
     * Das JSON geht als String ohne Media-Type hinaus. So schreibt Spring es
     * unverändert und setzt es nicht noch einmal in Anführungszeichen.
     *
     * Gibt false zurück, wenn der Client nicht mehr erreichbar ist:
     * - IOException: Die Verbindung ist abgebrochen.
     * - IllegalStateException ("already completed"): Der Client hat genau
     *   während des Sendens getrennt.
     * Beides fangen wir ab. Flöge die Ausnahme aus publish heraus, bekämen
     * die übrigen Clients des Raums die Nachricht nicht mehr.
     */
    private boolean send(SseEmitter emitter, DeliveryEvent event) {
        String eventId = event.id().toString();
        String json = event.json();
        SseEmitter.SseEventBuilder sseEvent = SseEmitter.event().id(eventId).data(json);
        try {
            emitter.send(sseEvent);
            return true;
        } catch (IOException | IllegalStateException exception) {
            return false;
        }
    }

    /**
     * Hängt ein neues Ereignis hinten an und wirft das älteste weg, wenn
     * die Liste sonst zu lang würde.
     *
     * Warum synchronized: Die Methode kann gleichzeitig aus mehreren
     * Threads aufgerufen werden, zum Beispiel vom RabbitMQ-Listener und
     * von einem Client, der sich gerade verbindet. synchronized lässt immer
     * nur einen Thread auf einmal in die Methoden dieses Raums, die anderen
     * warten, bis er fertig ist.
     *
     * remove(0) verschiebt alle übrigen Einträge um eine Stelle. Bei 1000
     * Einträgen ist das schnell genug und viel leichter zu lesen als ein
     * Ringpuffer.
     */
    public synchronized void add(DeliveryEvent event) {
        events.add(event);
        if (events.size() > MAX_EVENTS) {
            events.remove(0);
        }
    }

    /**
     * Beantwortet, was ein Client beim Verbinden aus der Liste bekommt.
     *
     * Genau drei Fälle (Spezifikation 3.6):
     * - keine Last-Event-ID: nichts, der Client bekommt ab jetzt nur live,
     * - die ID steht in der Liste: alle Ereignisse danach,
     * - die ID ist unbekannt: die ganze Liste, damit er möglichst wenig
     *   verpasst.
     *
     * Warum synchronized: Während wir die Liste durchgehen, darf kein
     * anderer Thread etwas anhängen oder entfernen. Sonst könnten wir ein
     * Ereignis übersehen oder eines doppelt erwischen.
     *
     * Zurück kommt immer eine neue Liste. Wer sie bekommt, kann sie in Ruhe
     * lesen, auch wenn in den Raum inzwischen neue Ereignisse kommen.
     */
    public synchronized List<DeliveryEvent> eventsAfter(String lastEventId) {
        List<DeliveryEvent> result = new ArrayList<>();
        if (lastEventId == null) {
            return result;
        }

        int position = positionOf(lastEventId);
        if (position == -1) {
            result.addAll(events);
            return result;
        }

        for (int i = position + 1; i < events.size(); i++) {
            DeliveryEvent event = events.get(i);
            result.add(event);
        }
        return result;
    }

    /**
     * Sucht die Stelle des Ereignisses mit dieser ID, oder -1, wenn es
     * nicht (mehr) in der Liste steht.
     *
     * Verglichen wird als Text, weil die Last-Event-ID als Text vom Client
     * kommt. Eine unsinnige ID ist damit einfach "unbekannt" und kein
     * Fehler.
     */
    private int positionOf(String lastEventId) {
        for (int i = 0; i < events.size(); i++) {
            DeliveryEvent event = events.get(i);
            String eventId = event.id().toString();
            if (eventId.equals(lastEventId)) {
                return i;
            }
        }
        return -1;
    }
}
