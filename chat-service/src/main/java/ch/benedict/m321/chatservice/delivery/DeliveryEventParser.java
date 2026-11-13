package ch.benedict.m321.chatservice.delivery;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Macht aus den Bytes vom Exchange chat.delivery ein DeliveryEvent.
 *
 * Der wichtigste Punkt steht in Spezifikation 1.1: Das JSON geht unverändert
 * an die Clients. Wir lesen deshalb nur id und roomId heraus und behalten
 * daneben den Originaltext. Würden wir die Nachricht in ein Java-Objekt
 * umwandeln und wieder zu JSON machen, könnte sich unterwegs etwas ändern,
 * zum Beispiel die Reihenfolge der Felder oder das Format von sentAt.
 *
 * Die Kopfzeile __TypeId__ beachten wir nicht. Sie nennt eine Java-Klasse
 * des Senders, und der Live-Weg soll von keiner Java-Klasse abhängen.
 */
@Component
@RequiredArgsConstructor
public class DeliveryEventParser {

    /** Der ObjectMapper von Spring Boot, derselbe wie im übrigen Dienst. */
    private final ObjectMapper objectMapper;

    /**
     * Liest eine Nachricht vom Exchange.
     *
     * Wirft IllegalArgumentException, wenn die Bytes kein brauchbares JSON
     * sind oder id bzw. roomId fehlen. Ohne diese zwei Werte können wir die
     * Nachricht weder einem Raum zuordnen noch nachliefern.
     */
    public DeliveryEvent parse(byte[] body) {
        String json = new String(body, StandardCharsets.UTF_8);
        DeliveryIds ids = readIds(json);

        UUID id = ids.id();
        if (id == null) {
            throw new IllegalArgumentException("Nachricht ohne id: " + json);
        }

        UUID roomId = ids.roomId();
        if (roomId == null) {
            throw new IllegalArgumentException("Nachricht ohne roomId: " + json);
        }

        return new DeliveryEvent(id, roomId, json);
    }

    /**
     * Liest id und roomId aus dem Text.
     *
     * Eigene Methode, damit die geprüfte JsonProcessingException von Jackson
     * an einer einzigen Stelle in eine IllegalArgumentException übersetzt
     * wird. Der Text "null" ergibt bei Jackson kein Objekt, sondern null.
     * Auch das ist keine brauchbare Nachricht.
     */
    private DeliveryIds readIds(String json) {
        DeliveryIds ids;
        try {
            ids = objectMapper.readValue(json, DeliveryIds.class);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("Kein gültiges JSON: " + json, exception);
        }

        if (ids == null) {
            throw new IllegalArgumentException("Leere Nachricht: " + json);
        }
        return ids;
    }
}
