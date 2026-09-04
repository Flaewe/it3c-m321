package ch.benedict.m321.chatservice.service;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * Die Regeln des chat-service.
 *
 * Hier — und nur hier — bekommt eine Nachricht ihre Identität und ihre
 * Zeit. Beides vergibt der Server, damit alle Empfänger dieselbe
 * Reihenfolge und dieselbe ID sehen.
 */
@Service
public class MessageService {

    private static final Logger log = LoggerFactory.getLogger(MessageService.class);

    private final MessagePublisher messagePublisher;

    public MessageService(MessagePublisher messagePublisher) {
        this.messagePublisher = messagePublisher;
    }

    /**
     * Nimmt eine Nachricht an und gibt zurück, unter welcher ID sie im
     * System unterwegs ist.
     *
     * Der Rückgabewert bedeutet ausdrücklich NICHT "gespeichert" — die
     * Nachricht liegt zu diesem Zeitpunkt erst in der Queue.
     */
    public AcceptedResponse accept(SendMessageRequest request) {
        int contentLength = request.content().length();
        log.info("Message received for room {} with {} characters", request.roomId(), contentLength);

        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();

        ChatMessage message = new ChatMessage(
                messageId,
                request.roomId(),
                request.senderId(),
                request.senderName(),
                request.content(),
                sentAt);

        messagePublisher.publish(message);

        return new AcceptedResponse(messageId, sentAt);
    }
}
