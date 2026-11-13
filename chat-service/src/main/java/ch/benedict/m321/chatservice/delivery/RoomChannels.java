package ch.benedict.m321.chatservice.delivery;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Alle Räume dieser Instanz, je mit ihrem eigenen RoomChannel.
 *
 * Jeder Raum hat seine eigene Liste und später seine eigenen Clients. Die
 * Sperre gilt deshalb pro Raum (Spezifikation 3.6): Wer in Raum A
 * nachliefert, hält Raum B nicht auf.
 *
 * Ein Raum entsteht, sobald er zum ersten Mal gebraucht wird, und bleibt
 * dann, solange die Instanz läuft. Eine Obergrenze für die Zahl der Räume
 * gibt es nicht (Spezifikation 1.4).
 */
@Component
public class RoomChannels {

    /** Ein RoomChannel pro Raum-ID. */
    private final Map<UUID, RoomChannel> channels = new HashMap<>();

    /**
     * Gibt den RoomChannel dieses Raums zurück und legt ihn an, falls es
     * ihn noch nicht gibt.
     *
     * synchronized, weil der RabbitMQ-Listener und verbindende Clients
     * gleichzeitig nach einem Raum fragen können. Ohne Sperre könnten zwei
     * Threads denselben fehlenden Raum gleichzeitig anlegen, und einer der
     * beiden RoomChannels ginge verloren, samt seinen Ereignissen.
     */
    public synchronized RoomChannel channelFor(UUID roomId) {
        RoomChannel channel = channels.get(roomId);
        if (channel == null) {
            channel = new RoomChannel();
            channels.put(roomId, channel);
        }
        return channel;
    }
}
