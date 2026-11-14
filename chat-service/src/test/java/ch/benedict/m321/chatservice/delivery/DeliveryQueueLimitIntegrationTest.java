package ch.benedict.m321.chatservice.delivery;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.MessagePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die Grenze aus Spezifikation 2 und 3.5 bei einer hängenden
 * Instanz: Ihre Queue hält nie mehr als 1000 Nachrichten (Szenario S6).
 *
 * Entstanden aus der Abnahme (Plan, Schritt 9): Dort zeigte die Queue
 * 1250, nämlich 1000 wartende und 250 schon ausgelieferte, aber
 * unbestätigte. x-max-length begrenzt nur die wartenden. Jetzt dürfen 999
 * warten, und höchstens 1 ist unbestätigt (prefetch 1): zusammen 1000.
 *
 * Eine hängende Instanz stellen wir nach, ohne den Produktionscode zu
 * ändern: Der Test hält die Sperre eines RoomChannel fest. Der Listener
 * bleibt dann bei der ersten Nachricht dieses Raums in publish stecken,
 * weil publish dieselbe Sperre braucht. Der Consumer bleibt aber beim
 * Broker angemeldet, genau wie bei einer angehaltenen Instanz in S6.
 */
@SpringBootTest
@Testcontainers
class DeliveryQueueLimitIntegrationTest {

    /** Mehr als die Grenze, damit drop-head wirklich greifen muss. */
    private static final int MESSAGES_TO_SEND = 2000;

    /** Die Grenze aus Spezifikation 2: so viele dürfen insgesamt in der Queue stehen. */
    private static final int QUEUE_LIMIT = 1000;

    /** So viele dürfen davon warten (x-max-length), siehe RabbitConfig. */
    private static final int MAX_WAITING = 999;

    /** So viele dürfen davon ausgeliefert, aber unbestätigt sein (prefetch). */
    private static final int MAX_UNACKNOWLEDGED = 1;

    /** So lange warten wir höchstens, bis die Queue voll ist. */
    private static final long MAX_WAIT_MILLIS = 15_000;

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MessagePublisher messagePublisher;

    @Autowired
    private RoomChannels roomChannels;

    /** S6 im Kleinen: Die Instanz hängt, 2000 Nachrichten kommen, und die Queue hält trotzdem höchstens 1000. */
    @Test
    void queueOfAHangingInstanceNeverHoldsMoreThanTheLimit() throws Exception {
        UUID roomId = UUID.randomUUID();
        RoomChannel channel = roomChannels.channelFor(roomId);
        waitForDeliveryQueue();

        String[] counts;
        synchronized (channel) {
            // Ab hier "hängt" die Instanz für diesen Raum.
            for (int i = 1; i <= MESSAGES_TO_SEND; i++) {
                ChatMessage message = createMessage(roomId, "Nachricht " + i);
                messagePublisher.publish(message);
            }
            counts = waitUntilReadyReachesLimit();
        }
        // Sperre freigegeben: Die Instanz arbeitet weiter.

        assertNotNull(counts, "Die eigene Queue wurde nicht gefunden");
        int ready = Integer.parseInt(counts[1]);
        int unacknowledged = Integer.parseInt(counts[2]);
        int messages = Integer.parseInt(counts[3]);

        assertEquals(MAX_WAITING, ready, "So viele warten in der Queue");
        assertTrue(unacknowledged <= MAX_UNACKNOWLEDGED, "Unbestätigt: " + unacknowledged);
        assertTrue(messages <= QUEUE_LIMIT, "Die Queue hält " + messages + " Nachrichten");
    }

    /**
     * Wartet, bis in der eigenen Queue 999 Nachrichten warten, und gibt
     * ihre Zahlen zurück: Name, messages_ready, messages_unacknowledged,
     * messages. Nach der Wartezeit kommt zurück, was gerade da ist.
     */
    private String[] waitUntilReadyReachesLimit() throws Exception {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        String[] counts = readDeliveryQueueCounts();
        while (!reachedLimit(counts) && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            counts = readDeliveryQueueCounts();
        }
        return counts;
    }

    /** Ob in der Queue schon so viele warten, wie die Grenze erlaubt. */
    private boolean reachedLimit(String[] counts) {
        if (counts == null) {
            return false;
        }
        int ready = Integer.parseInt(counts[1]);
        return ready >= MAX_WAITING;
    }

    /** Wartet, bis der Broker die eigene Queue der Instanz kennt. */
    private void waitForDeliveryQueue() throws Exception {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        String[] counts = readDeliveryQueueCounts();
        while (counts == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            counts = readDeliveryQueueCounts();
        }
    }

    /**
     * Liest die Zahlen der amq.gen-Queue mit rabbitmqctl, so wie S6 von
     * Hand. --silent lässt Kopfzeilen weg. Gibt null zurück, wenn es die
     * Queue (noch) nicht gibt.
     */
    private String[] readDeliveryQueueCounts() throws Exception {
        ExecResult result = rabbitMq.execInContainer("rabbitmqctl", "--silent", "list_queues",
                "name", "messages_ready", "messages_unacknowledged", "messages");
        String output = result.getStdout();
        String[] lines = output.split("\n");
        for (String line : lines) {
            if (line.startsWith("amq.gen-")) {
                return line.split("\t");
            }
        }
        return null;
    }

    /** Baut eine vollständige Nachricht für diesen Raum. */
    private ChatMessage createMessage(UUID roomId, String content) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "sub-s6", "Test", content, sentAt);
    }
}
