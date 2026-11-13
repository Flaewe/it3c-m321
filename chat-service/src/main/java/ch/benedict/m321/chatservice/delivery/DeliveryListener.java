package ch.benedict.m321.chatservice.delivery;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageListener;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Nimmt jede Nachricht aus der eigenen Queue dieser Instanz entgegen und
 * gibt sie an den Raum, zu dem sie gehört. Der Raum merkt sie sich und
 * schickt sie an seine verbundenen Clients.
 *
 * Bewusst ein einfacher MessageListener mit rohen Bytes und kein
 * @RabbitListener: Den Container dazu richten wir in RabbitConfig selbst
 * ein, weil er den Namen der Queue nach jeder Neuverbindung neu abfragen
 * muss.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class DeliveryListener implements MessageListener {

    private final DeliveryEventParser deliveryEventParser;

    private final RoomChannels roomChannels;

    /**
     * Wird für jede Nachricht aus der Queue einmal aufgerufen.
     *
     * Eine kaputte Nachricht wird protokolliert und verworfen, nicht
     * weitergeworfen. Sie würde beim nächsten Versuch genauso scheitern.
     */
    @Override
    public void onMessage(Message message) {
        byte[] body = message.getBody();

        DeliveryEvent event;
        try {
            event = deliveryEventParser.parse(body);
        } catch (IllegalArgumentException exception) {
            log.warn("Discarding unreadable delivery message: {}", exception.getMessage());
            return;
        }

        UUID roomId = event.roomId();
        RoomChannel channel = roomChannels.channelFor(roomId);
        channel.publish(event);
        log.debug("Published message {} in room {}", event.id(), roomId);
    }
}
