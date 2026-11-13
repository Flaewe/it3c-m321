package ch.benedict.m321.chatservice.delivery;

import java.util.ArrayList;
import java.util.List;

/**
 * Das Gedächtnis eines einzelnen Raums in dieser Instanz.
 *
 * Ein Client, der kurz die Verbindung verliert, soll die verpassten
 * Nachrichten nachholen können, ohne dass wir die Datenbank fragen
 * (Spezifikation 3.6). Dafür merkt sich der Raum seine letzten
 * {@value #MAX_EVENTS} Ereignisse in der Reihenfolge, in der sie
 * angekommen sind.
 *
 * Die Liste lebt nur im Speicher. Startet die Instanz neu, ist sie leer.
 */
public class RoomChannel {

    /**
     * So viele Ereignisse hält ein Raum höchstens. Dieselbe Grenze wie
     * x-max-length der Queue: Mehr als 1000 verpasste Nachrichten holt
     * man auf dem Live-Weg ohnehin nicht nach.
     */
    static final int MAX_EVENTS = 1000;

    /** Die Ereignisse, das älteste vorne, das neueste hinten. */
    private final List<DeliveryEvent> events = new ArrayList<>();

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
