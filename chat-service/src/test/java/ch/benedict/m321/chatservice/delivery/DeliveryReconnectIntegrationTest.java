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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft die riskanteste Stelle des Live-Wegs: Was passiert, wenn RabbitMQ
 * die Verbindung der Instanz schliesst?
 *
 * In Spezifikation 3.5 haben wir gemessen, dass RabbitMQ eine hängende
 * Verbindung nach etwa zwei Minuten schliesst. Die exklusive Queue ist dann
 * weg. Die Instanz muss sich neu verbinden, eine NEUE Queue mit neuem Namen
 * anlegen, sie an chat.delivery binden und wirklich daraus abholen. Fehlt
 * einer dieser Schritte, stellt die Instanz nie wieder etwas zu, und ohne
 * diesen Test würde es niemand merken.
 *
 * Statt zwei Minuten auf ausbleibende Heartbeats zu warten, schliessen wir
 * die Verbindungen mit rabbitmqctl selbst. Für die Instanz ist das
 * dasselbe: Der Broker hat die Verbindung beendet.
 */
@SpringBootTest
@Testcontainers
class DeliveryReconnectIntegrationTest {

    /** So lange darf die Neuverbindung höchstens dauern. */
    private static final int MAX_RECONNECT_MILLIS = 15_000;

    /** So lange warten wir höchstens auf eine einzelne Nachricht. */
    private static final int MAX_MESSAGE_MILLIS = 10_000;

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

    @Test
    void usesNewQueueAfterTheBrokerClosedTheConnection() throws Exception {
        // 1. Die erste Queue der Instanz abwarten und ihren Namen merken.
        String oldName = waitForQueueWithConsumer(null);
        assertNotNull(oldName, "Die Instanz hat beim Start keine Queue mit Consumer angelegt");
        assertTrue(oldName.startsWith("amq.gen-"), oldName);
        String nameBeforeClose = deliveryQueue.getActualName();
        assertEquals(oldName, nameBeforeClose);

        // 2. Alle Verbindungen schliessen, wie bei ausbleibenden Heartbeats.
        rabbitmqctl("close_all_connections", "Test");

        // 3. Warten, bis eine Queue mit ANDEREM Namen und einem Consumer da ist.
        String newName = waitForQueueWithConsumer(oldName);
        assertNotNull(newName, "Nach der Neuverbindung gibt es keine neue Queue mit Consumer");
        assertNotEquals(oldName, newName);
        List<String> namesNow = readDeliveryQueueNames();
        assertFalse(namesNow.contains(oldName), "Die alte Queue ist noch da: " + namesNow);
        String nameAfterReconnect = deliveryQueue.getActualName();
        assertEquals(newName, nameAfterReconnect);

        // 4. Die neue Queue hängt am Fanout.
        String bindings = rabbitmqctl("list_bindings", "source_name", "destination_name");
        String expectedBinding = "chat.delivery\t" + newName;
        assertTrue(bindings.contains(expectedBinding), bindings);

        // 5. Der Container holt wirklich aus der neuen Queue ab.
        UUID roomId = UUID.randomUUID();
        ChatMessage message = createMessage(roomId);
        messagePublisher.publish(message);

        RoomChannel channel = roomChannels.channelFor(roomId);
        List<DeliveryEvent> events = waitForEvents(channel, 1);
        assertEquals(1, events.size(), "Die Nachricht kam nach der Neuverbindung nicht an");
        DeliveryEvent event = events.get(0);
        assertEquals(message.id(), event.id());
    }

    /**
     * Wartet, bis der Broker eine amq.gen-Queue mit genau einem Consumer
     * zeigt, deren Name nicht excludedName ist. Gibt ihren Namen zurück,
     * oder null, wenn nach der Wartezeit keine da ist.
     *
     * Der Consumer zählt mit: Eine Queue ohne Consumer hiesse, dass die
     * Instanz sie zwar angelegt hat, aber nicht daraus liest.
     */
    private String waitForQueueWithConsumer(String excludedName) throws Exception {
        long deadline = System.currentTimeMillis() + MAX_RECONNECT_MILLIS;
        String found = findQueueWithConsumer(excludedName);
        while (found == null && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            found = findQueueWithConsumer(excludedName);
        }
        return found;
    }

    /** Sucht einmal in list_queues, siehe waitForQueueWithConsumer. */
    private String findQueueWithConsumer(String excludedName) throws Exception {
        String output = rabbitmqctl("list_queues", "name", "consumers");
        String[] lines = output.split("\n");
        for (String line : lines) {
            String[] columns = line.split("\t");
            String name = columns[0];
            boolean isDeliveryQueue = name.startsWith("amq.gen-");
            boolean isExcluded = name.equals(excludedName);
            boolean hasOneConsumer = columns.length > 1 && columns[1].equals("1");
            if (isDeliveryQueue && !isExcluded && hasOneConsumer) {
                return name;
            }
        }
        return null;
    }

    /** Alle Namen von amq.gen-Queues, die der Broker gerade kennt. */
    private List<String> readDeliveryQueueNames() throws Exception {
        String output = rabbitmqctl("list_queues", "name");
        String[] lines = output.split("\n");

        List<String> names = new ArrayList<>();
        for (String line : lines) {
            if (line.startsWith("amq.gen-")) {
                names.add(line);
            }
        }
        return names;
    }

    /**
     * Wartet, bis im Raum so viele Ereignisse stehen. Eine unbekannte
     * Last-Event-ID liefert laut 3.6 die ganze Liste.
     */
    private List<DeliveryEvent> waitForEvents(RoomChannel channel, int expectedCount) throws Exception {
        String unknownId = "unbekannt";
        long deadline = System.currentTimeMillis() + MAX_MESSAGE_MILLIS;
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
        return new ChatMessage(messageId, roomId, "anna", "Anna Muster", "Hallo nach der Neuverbindung", sentAt);
    }
}
