package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.dto.IncomingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * Macht aus den Bytes einer AMQP-Nachricht ein Java-Objekt.
 *
 * <p><b>Warum von Hand und nicht mit einem Konverter von Spring.</b> Der
 * chat-service setzt beim Senden die Kopfzeile
 * <code>__TypeId__ = ch.benedict.m321.chatservice.dto.ChatMessage</code>.
 * Ein typisierender Konverter wie Jackson2JsonMessageConverter würde
 * versuchen, genau diese Klasse zu laden. Es gibt sie hier nicht und soll
 * sie hier nicht geben — jede echte Nachricht würde deshalb mit einer
 * ClassNotFoundException in der Dead-Letter-Queue landen statt in der
 * Datenbank.
 *
 * <p>Umgekehrt trägt eine von Hand eingelegte Nachricht diese Kopfzeile
 * gar nicht, und derselbe Konverter wüsste dann nicht, wohin damit.
 *
 * <p>Dieser Parser liest nur den Rumpf und ignoriert jede Kopfzeile. Damit
 * sind beide Fälle erledigt, und es gilt, was der chat-service in seinem
 * eigenen Kommentar verlangt: der Vertrag ist das JSON, nicht die Klasse.
 */
@Component
public class MessageParser {

    private final ObjectMapper objectMapper;

    /**
     * Nimmt den ObjectMapper von Spring Boot.
     *
     * Der kann bereits ISO-8601 mit Zeitzone in Instant umwandeln — genau
     * das Format, in dem sentAt ankommt.
     */
    public MessageParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Liest eine Nachricht aus ihrem Rumpf.
     *
     * @throws MessageParseException wenn der Rumpf kein gültiges JSON ist
     *         oder ein Pflichtfeld fehlt. Beides ist ein dauerhafter Fehler.
     */
    public IncomingMessage parse(byte[] body) {
        try {
            IncomingMessage message = objectMapper.readValue(body, IncomingMessage.class);
            pruefePflichtfelder(message);
            return message;
        } catch (MessageParseException bereitsGeprueft) {
            throw bereitsGeprueft;
        } catch (Exception problem) {
            String rumpf = new String(body, StandardCharsets.UTF_8);
            throw new MessageParseException("Message body is not valid JSON: " + rumpf, problem);
        }
    }

    /**
     * Prüft, dass kein Feld fehlt.
     *
     * Jackson setzt fehlende Felder stillschweigend auf null. Ohne diese
     * Prüfung käme der Fehler erst in der Datenbank an — als Verletzung
     * einer NOT-NULL-Bedingung, mitten im Stapel, und dann wäre unklar,
     * welche der 500 Nachrichten schuld ist.
     */
    private void pruefePflichtfelder(IncomingMessage message) {
        if (message.id() == null) {
            throw new MessageParseException("Field 'id' is missing", null);
        }
        if (message.roomId() == null) {
            throw new MessageParseException("Field 'roomId' is missing", null);
        }
        if (message.senderId() == null) {
            throw new MessageParseException("Field 'senderId' is missing", null);
        }
        if (message.senderName() == null) {
            throw new MessageParseException("Field 'senderName' is missing", null);
        }
        if (message.content() == null) {
            throw new MessageParseException("Field 'content' is missing", null);
        }
        if (message.sentAt() == null) {
            throw new MessageParseException("Field 'sentAt' is missing", null);
        }
    }
}
