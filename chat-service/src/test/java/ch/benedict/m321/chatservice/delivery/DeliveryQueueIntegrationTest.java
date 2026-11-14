package ch.benedict.m321.chatservice.delivery;

import ch.benedict.m321.chatservice.dto.ChatMessage;
import ch.benedict.m321.chatservice.service.MessagePublisher;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Queue;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft gegen einen echten RabbitMQ, dass jede Instanz ihre eigene Queue
 * mit den Eigenschaften aus Spezifikation 2 anlegt und dass Nachrichten
 * über diese Queue im richtigen Raum landen.
 *
 * Gelesen wird mit rabbitmqctl, also genau so, wie es S4 und S6 von Hand
 * tun. Was der Broker anzeigt, zählt, nicht was Spring meint, angelegt zu
 * haben.
 */
@SpringBootTest
@Testcontainers
class DeliveryQueueIntegrationTest {

    /** So lange warten wir höchstens auf etwas, das im Hintergrund passiert. */
    private static final int MAX_WAIT_MILLIS = 10_000;

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private MessagePublisher messagePublisher;

    @Autowired
    private RoomChannels roomChannels;

    @Autowired
    @Qualifier("deliveryQueue")
    private Queue deliveryQueue;

    /**
     * Genau eine Queue amq.gen-..., nicht durable, auto-delete, exclusive,
     * mit x-max-length 999 und x-overflow drop-head (999 statt 1000: Plan,
     * Schritt 9).
     */
    @Test
    void declaresOwnQueueWithTheSpecifiedProperties() throws Exception {
        List<String[]> queues = waitForDeliveryQueues();

        assertEquals(1, queues.size());
        String[] columns = queues.get(0);
        String name = columns[0];
        String durable = columns[1];
        String autoDelete = columns[2];
        String exclusive = columns[3];
        String arguments = columns[4];

        assertTrue(name.startsWith("amq.gen-"), "Name vom Broker erwartet: " + name);
        assertEquals("false", durable);
        assertEquals("true", autoDelete);
        assertEquals("true", exclusive);
        assertTrue(arguments.contains("x-max-length"), arguments);
        assertTrue(arguments.contains("999"), arguments);
        assertTrue(arguments.contains("x-overflow"), arguments);
        assertTrue(arguments.contains("drop-head"), arguments);

        String actualName = deliveryQueue.getActualName();
        assertEquals(name, actualName);
    }

    /** Die Queue hängt am Fanout chat.delivery, sonst bekäme sie nichts. */
    @Test
    void bindsOwnQueueToTheDeliveryExchange() throws Exception {
        List<String[]> queues = waitForDeliveryQueues();
        assertEquals(1, queues.size());
        String[] columns = queues.get(0);
        String queueName = columns[0];

        String output = rabbitmqctl("list_bindings", "source_name", "destination_name");

        String expectedLine = "chat.delivery\t" + queueName;
        assertTrue(output.contains(expectedLine), output);
    }

    /**
     * Der ganze Weg ohne HTTP: veröffentlichen, über die eigene Queue
     * abholen, im RoomChannel des Raums ablegen.
     */
    @Test
    void putsPublishedMessageIntoItsRoomChannel() throws Exception {
        waitForDeliveryQueues();
        UUID roomId = UUID.randomUUID();
        ChatMessage message = createMessage(roomId);

        messagePublisher.publish(message);

        RoomChannel channel = roomChannels.channelFor(roomId);
        List<DeliveryEvent> events = waitForEvents(channel, 1);
        assertEquals(1, events.size());
        DeliveryEvent event = events.get(0);
        assertEquals(message.id(), event.id());
        assertEquals(roomId, event.roomId());
    }

    /**
     * Wartet, bis der Broker die Queue der Instanz anzeigt, und gibt die
     * Zeilen aller amq.gen-Queues zurück, schon in Spalten zerlegt.
     *
     * Warten ist nötig, weil der Container im Hintergrund startet.
     */
    private List<String[]> waitForDeliveryQueues() throws Exception {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        List<String[]> queues = readDeliveryQueues();
        while (queues.isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            queues = readDeliveryQueues();
        }
        return queues;
    }

    /** Liest alle Queues, deren Namen der Broker vergeben hat. */
    private List<String[]> readDeliveryQueues() throws Exception {
        String output = rabbitmqctl("list_queues", "name", "durable", "auto_delete", "exclusive", "arguments");
        String[] lines = output.split("\n");

        List<String[]> queues = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("amq.gen-")) {
                String[] columns = line.split("\t");
                queues.add(columns);
            }
        }
        return queues;
    }

    /**
     * Wartet, bis im Raum so viele Ereignisse stehen. Eine unbekannte
     * Last-Event-ID liefert laut 3.6 die ganze Liste.
     */
    private List<DeliveryEvent> waitForEvents(RoomChannel channel, int expectedCount) throws Exception {
        String unknownId = "unbekannt";
        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        List<DeliveryEvent> events = channel.eventsAfter(unknownId);
        while (events.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
            events = channel.eventsAfter(unknownId);
        }
        return events;
    }

    /**
     * Ruft rabbitmqctl im Container auf. --silent lässt Kopfzeilen und
     * Hinweise weg, übrig bleiben nur die Datenzeilen, eine Spalte pro
     * Tabulator.
     */
    private String rabbitmqctl(String... arguments) throws Exception {
        List<String> command = new ArrayList<>();
        command.add("rabbitmqctl");
        command.add("--silent");
        for (String argument : arguments) {
            command.add(argument);
        }
        String[] commandArray = command.toArray(new String[0]);

        ExecResult result = rabbitMq.execInContainer(commandArray);
        return result.getStdout();
    }

    /** Baut eine vollständige Nachricht für diesen Raum. */
    private ChatMessage createMessage(UUID roomId) {
        UUID messageId = UUID.randomUUID();
        Instant sentAt = Instant.now();
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", "Hallo Live-Weg", sentAt);
    }
}
