package ch.benedict.m321.chatservice.delivery;

import ch.benedict.m321.chatservice.config.QueueNames;
import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Der Live-Weg von aussen, einmal ganz durch: POST /messages, RabbitMQ,
 * eigene Queue, RoomChannel, GET /stream.
 *
 * Die Wartezeit des Servers für offene Anfragen ist hier absichtlich auf
 * 2 Sekunden gestellt. So beweist der Test "Stream bleibt offen" in ein
 * paar Sekunden, dass unser Stream diese Vorgabe übersteuert.
 *
 * Jeder Test benutzt eigene Räume (UUID.randomUUID()). Die Tests teilen sich
 * denselben Spring-Kontext und damit dieselben RoomChannels.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.mvc.async.request-timeout=2s")
@Testcontainers
class StreamIntegrationTest {

    /** So lange warten wir höchstens auf ein Ereignis beim Client. */
    private static final long MAX_WAIT_MILLIS = 10_000;

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitMq = new RabbitMQContainer("rabbitmq:3.13-management");

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private RoomChannels roomChannels;

    @Autowired
    private RabbitAdmin rabbitAdmin;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    /**
     * Die Nachricht kommt im richtigen Raum genau einmal an, mit der
     * Nachrichten-ID als id: und mit genau dem JSON, das auf dem Exchange
     * lag. Der Umlaut beweist, dass UTF-8 bis zum Client durchgeht. Der
     * Client in Raum B bekommt nichts.
     */
    @Test
    void deliversTheExactJsonOnlyToTheRightRoom() throws Exception {
        UUID roomA = UUID.randomUUID();
        UUID roomB = UUID.randomUUID();
        Queue testQueue = bindTestQueueToDeliveryExchange();

        try (SseTestClient clientA = connect(roomA, null);
             SseTestClient clientB = connect(roomB, null)) {
            waitForSubscribers(roomA, 1);
            waitForSubscribers(roomB, 1);

            AcceptedResponse accepted = sendMessage(roomA, "Grüsse aus Zürich");

            String jsonOnExchange = receiveRawJson(testQueue);
            clientA.waitForEvents(1, MAX_WAIT_MILLIS);
            // Noch kurz warten, damit eine zweite, falsche Zustellung auffallen würde.
            Thread.sleep(500);

            List<String> idsA = clientA.ids();
            List<String> dataA = clientA.data();
            assertEquals(1, idsA.size());
            String expectedId = accepted.id().toString();
            assertEquals(expectedId, idsA.get(0));
            assertEquals(jsonOnExchange, dataA.get(0));
            assertTrue(jsonOnExchange.contains("Grüsse aus Zürich"), jsonOnExchange);

            List<String> idsB = clientB.ids();
            assertEquals(0, idsB.size());
        }
    }

    /** Die Antwort ist ein Event-Stream (Spezifikation 1.2). */
    @Test
    void answersWithEventStreamContentType() throws Exception {
        UUID roomId = UUID.randomUUID();

        try (SseTestClient client = connect(roomId, null)) {
            waitForSubscribers(roomId, 1);
            sendMessage(roomId, "Hallo");
            client.waitForEvents(1, MAX_WAIT_MILLIS);

            String contentType = client.contentType();
            assertNotNull(contentType);
            assertTrue(contentType.startsWith("text/event-stream"), contentType);
        }
    }

    /**
     * Der Server schliesst den Stream nicht von sich aus (1.2). Wir warten
     * länger als die 2 Sekunden, die der Server hier als Vorgabe hat. Die
     * Nachricht muss trotzdem ankommen.
     */
    @Test
    void keepsTheStreamOpenLongerThanTheServerTimeout() throws Exception {
        UUID roomId = UUID.randomUUID();

        try (SseTestClient client = connect(roomId, null)) {
            waitForSubscribers(roomId, 1);
            Thread.sleep(3000);

            sendMessage(roomId, "Nach drei Sekunden");
            client.waitForEvents(1, MAX_WAIT_MILLIS);

            List<String> ids = client.ids();
            assertEquals(1, ids.size(), "Der Stream wurde vom Server geschlossen");
        }
    }

    /**
     * Ein getrennter Client wird bald ausgetragen. Das erste Senden nach dem
     * Trennen kann noch gelingen, weil es nur in einen Puffer geht. Darum
     * senden wir jede Sekunde eine Nachricht, bis er weg ist.
     */
    @Test
    void removesClientAfterItDisconnected() throws Exception {
        UUID roomId = UUID.randomUUID();
        RoomChannel channel = roomChannels.channelFor(roomId);

        SseTestClient client = connect(roomId, null);
        waitForSubscribers(roomId, 1);
        client.close();

        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        int count = channel.subscriberCount();
        while (count > 0 && System.currentTimeMillis() < deadline) {
            sendMessage(roomId, "Ist noch jemand da?");
            Thread.sleep(1000);
            count = channel.subscriberCount();
        }

        assertEquals(0, count, "Der getrennte Client ist noch eingetragen");
    }

    /** Verbindet einen Test-Client mit dem Stream eines Raums. */
    private SseTestClient connect(UUID roomId, String lastEventId) {
        String url = "http://localhost:" + port + "/stream?roomId=" + roomId;
        return new SseTestClient(url, lastEventId);
    }

    /**
     * Wartet, bis der Server so viele Clients im Raum eingetragen hat. Erst
     * dann darf der Test senden: Eine Nachricht vor dem Eintragen bekäme der
     * Client laut 3.6 nie (ohne Last-Event-ID nur live).
     */
    private void waitForSubscribers(UUID roomId, int expectedCount) throws InterruptedException {
        RoomChannel channel = roomChannels.channelFor(roomId);
        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        int count = channel.subscriberCount();
        while (count < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
            count = channel.subscriberCount();
        }
        assertEquals(expectedCount, count, "Client wurde nicht eingetragen");
    }

    /** Schickt eine Nachricht über POST /messages, wie es ein echter Client tut. */
    private AcceptedResponse sendMessage(UUID roomId, String content) {
        SendMessageRequest request = new SendMessageRequest(roomId, "anna", "Anna Muster", content);
        ResponseEntity<AcceptedResponse> response =
                restTemplate.postForEntity("/messages", request, AcceptedResponse.class);
        assertEquals(202, response.getStatusCode().value());
        return response.getBody();
    }

    /**
     * Bindet eine eigene Queue an den Fanout, so wie es jede Instanz tut.
     * Über sie lesen wir mit, welche Bytes wirklich auf dem Exchange lagen.
     */
    private Queue bindTestQueueToDeliveryExchange() {
        Queue testQueue = new AnonymousQueue();
        rabbitAdmin.declareQueue(testQueue);

        FanoutExchange exchange = new FanoutExchange(QueueNames.DELIVERY_EXCHANGE);
        Binding binding = BindingBuilder.bind(testQueue).to(exchange);
        rabbitAdmin.declareBinding(binding);
        return testQueue;
    }

    /** Holt die rohen Bytes einer Nachricht aus der Test-Queue, als UTF-8-Text. */
    private String receiveRawJson(Queue testQueue) {
        String queueName = testQueue.getName();
        Message message = rabbitTemplate.receive(queueName, 5000);
        assertNotNull(message, "Auf dem Exchange kam nichts an");
        byte[] body = message.getBody();
        return new String(body, StandardCharsets.UTF_8);
    }
}
