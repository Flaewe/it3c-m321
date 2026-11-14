package ch.benedict.m321.chatservice.delivery;

import ch.benedict.m321.chatservice.ChatServiceApplication;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Szenario S3 als Test: zwei Instanzen des chat-service an EINEM RabbitMQ.
 *
 * Erst mit zwei Instanzen zeigt der Fanout seinen Sinn (Spezifikation 2
 * und 3.2): Jede Instanz hat ihre eigene Queue und bekommt jede Nachricht,
 * egal über welche Instanz sie gesendet wurde. Ohne diesen Test würde eine
 * gemeinsame Queue statt einer pro Instanz nicht auffallen.
 *
 * Kein @SpringBootTest: Der startet genau eine Instanz. Wir starten zwei
 * mit SpringApplicationBuilder, wie zwei Container in docker compose.
 */
@Testcontainers
class TwoInstancesIntegrationTest {

    /** So viele Nachrichten sendet jeder der beiden Absender. */
    private static final int MESSAGES_PER_SENDER = 200;

    /** So lange warten wir höchstens auf etwas, das im Hintergrund passiert. */
    private static final long MAX_WAIT_MILLIS = 20_000;

    @Container
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    private static ConfigurableApplicationContext instance1;

    private static ConfigurableApplicationContext instance2;

    private final RestTemplate restTemplate = new RestTemplate();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /** Startet beide Instanzen, nachdem Testcontainers den Broker gestartet hat. */
    @BeforeAll
    static void startTwoInstances() {
        instance1 = startInstance();
        instance2 = startInstance();
    }

    /** Fährt beide Instanzen wieder herunter. */
    @AfterAll
    static void stopTwoInstances() {
        if (instance1 != null) {
            instance1.close();
        }
        if (instance2 != null) {
            instance2.close();
        }
    }

    /** S3: Jeder Client in Raum A bekommt alle 400 genau einmal und pro Absender in Reihenfolge, der Client in Raum B keine. */
    @Test
    void bothClientsInRoomAGetAllMessagesExactlyOnceAndRoomBGetsNone() throws Exception {
        // Vorher: genau zwei eigene Queues, jede mit ihrem Consumer.
        int queuesWithConsumer = waitForDeliveryQueuesWithConsumer(2);
        assertEquals(2, queuesWithConsumer);

        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        int port1 = portOf(instance1);
        int port2 = portOf(instance2);

        try (SseTestClient clientA1 = connect(port1, roomA);
             SseTestClient clientA2 = connect(port2, roomA);
             SseTestClient clientB = connect(port2, roomB)) {
            waitForSubscribers(instance1, roomA, 1);
            waitForSubscribers(instance2, roomA, 1);
            waitForSubscribers(instance2, roomB, 1);

            sendMessages(port1, roomA, "sub-1");
            sendMessages(port2, roomA, "sub-2");

            int total = 2 * MESSAGES_PER_SENDER;
            clientA1.waitForEvents(total, MAX_WAIT_MILLIS);
            clientA2.waitForEvents(total, MAX_WAIT_MILLIS);
            // Noch warten, damit zu viele Ereignisse auffallen würden.
            Thread.sleep(2000);

            checkClientGotEverythingOnceInOrder(clientA1, "A1");
            checkClientGotEverythingOnceInOrder(clientA2, "A2");

            List<String> idsB = clientB.ids();
            assertEquals(0, idsB.size(), "Client B in Raum B hat Nachrichten aus Raum A bekommen");
        }
    }

    /**
     * Genau 400 Ereignisse, 400 verschiedene IDs, und pro Absender die
     * Nummern 1 bis 200 lückenlos in Reihenfolge.
     */
    private void checkClientGotEverythingOnceInOrder(SseTestClient client, String name) throws Exception {
        int total = 2 * MESSAGES_PER_SENDER;
        List<String> ids = client.ids();
        assertEquals(total, ids.size(), "Client " + name + ": Anzahl Ereignisse");

        Set<String> distinctIds = new HashSet<>(ids);
        assertEquals(total, distinctIds.size(), "Client " + name + ": ein Ereignis kam doppelt");

        List<String> expected = numberedContents(MESSAGES_PER_SENDER);
        List<String> fromSender1 = contentsOf(client, "sub-1");
        List<String> fromSender2 = contentsOf(client, "sub-2");
        assertEquals(expected, fromSender1, "Client " + name + ": Reihenfolge von sub-1");
        assertEquals(expected, fromSender2, "Client " + name + ": Reihenfolge von sub-2");
    }

    /**
     * Startet eine Instanz des chat-service gegen den Test-Broker.
     *
     * Die Werte gehen als Kommandozeilen-Argumente hinein. Die haben in
     * Spring Vorrang vor application.yml, dort stehen sonst Port 8080 und
     * der Broker "localhost:5672". server.port=0 heisst: ein freier Port,
     * damit sich die zwei Instanzen nicht in die Quere kommen.
     */
    private static ConfigurableApplicationContext startInstance() {
        String host = rabbitMq.getHost();
        Integer amqpPort = rabbitMq.getAmqpPort();
        String username = rabbitMq.getAdminUsername();
        String password = rabbitMq.getAdminPassword();

        SpringApplicationBuilder builder = new SpringApplicationBuilder(ChatServiceApplication.class);
        return builder.run(
                "--server.port=0",
                "--spring.rabbitmq.host=" + host,
                "--spring.rabbitmq.port=" + amqpPort,
                "--spring.rabbitmq.username=" + username,
                "--spring.rabbitmq.password=" + password);
    }

    /** Der freie Port, den sich die Instanz beim Start gesucht hat. */
    private int portOf(ConfigurableApplicationContext instance) {
        String port = instance.getEnvironment().getProperty("local.server.port");
        return Integer.parseInt(port);
    }

    /** Verbindet einen Test-Client mit dem Stream eines Raums an einer Instanz. */
    private SseTestClient connect(int port, UUID roomId) {
        String url = "http://localhost:" + port + "/stream?roomId=" + roomId;
        return new SseTestClient(url, null);
    }

    /**
     * Wartet, bis diese Instanz so viele Clients im Raum eingetragen hat.
     * Erst dann darf gesendet werden: Ohne Last-Event-ID gibt es nur live.
     */
    private void waitForSubscribers(ConfigurableApplicationContext instance, UUID roomId, int expectedCount)
            throws InterruptedException {
        RoomChannels roomChannels = instance.getBean(RoomChannels.class);
        RoomChannel channel = roomChannels.channelFor(roomId);
        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        int count = channel.subscriberCount();
        while (count < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            count = channel.subscriberCount();
        }
        assertEquals(expectedCount, count, "Client wurde nicht eingetragen");
    }

    /**
     * Schickt «Nachricht 1» bis «Nachricht 200» über POST /messages an diese
     * Instanz, eine nach der anderen.
     */
    private void sendMessages(int port, UUID roomId, String senderId) {
        String url = "http://localhost:" + port + "/messages";
        for (int i = 1; i <= MESSAGES_PER_SENDER; i++) {
            SendMessageRequest request = new SendMessageRequest(roomId, senderId, "Test", "Nachricht " + i);
            ResponseEntity<String> response = restTemplate.postForEntity(url, request, String.class);
            assertEquals(202, response.getStatusCode().value());
        }
    }

    /** Die Inhalte aller Ereignisse dieses Absenders beim Client, in Reihenfolge. */
    private List<String> contentsOf(SseTestClient client, String senderId) throws Exception {
        List<String> contents = new ArrayList<>();
        List<String> data = client.data();
        for (String json : data) {
            JsonNode message = objectMapper.readTree(json);
            String messageSender = message.get("senderId").asText();
            if (messageSender.equals(senderId)) {
                String content = message.get("content").asText();
                contents.add(content);
            }
        }
        return contents;
    }

    /** «Nachricht 1» bis «Nachricht count», so wie sendMessages sie verschickt. */
    private List<String> numberedContents(int count) {
        List<String> contents = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            contents.add("Nachricht " + i);
        }
        return contents;
    }

    /**
     * Wartet, bis der Broker so viele amq.gen-Queues mit genau einem
     * Consumer zeigt, und gibt die Anzahl zurück. Die Consumer starten im
     * Hintergrund, direkt nach dem Start ist vielleicht erst einer da.
     */
    private int waitForDeliveryQueuesWithConsumer(int expectedCount) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        int count = countDeliveryQueuesWithConsumer();
        while (count != expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(200);
            count = countDeliveryQueuesWithConsumer();
        }
        return count;
    }

    /** Zählt einmal in list_queues die amq.gen-Queues mit consumers 1. */
    private int countDeliveryQueuesWithConsumer() throws Exception {
        ExecResult result = rabbitMq.execInContainer("rabbitmqctl", "--silent", "list_queues", "name", "consumers");
        String output = result.getStdout();
        String[] lines = output.split("\n");

        int count = 0;
        for (String line : lines) {
            String[] columns = line.split("\t");
            boolean isDeliveryQueue = columns[0].startsWith("amq.gen-");
            boolean hasOneConsumer = columns.length > 1 && columns[1].equals("1");
            if (isDeliveryQueue && hasOneConsumer) {
                count = count + 1;
            }
        }
        return count;
    }
}
