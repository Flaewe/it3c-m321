package ch.benedict.m321.chatservice.delivery;

import ch.benedict.m321.chatservice.dto.AcceptedResponse;
import ch.benedict.m321.chatservice.dto.SendMessageRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.ResponseEntity;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Prüft das Nachholen mit Last-Event-ID gegen einen echten RabbitMQ
 * (Spezifikation 3.6, Szenario S7).
 *
 * Wichtig für das Warten: Ein Client, der mit Last-Event-ID nachholt, ist
 * erst eingetragen, NACHDEM das Nachliefern fertig ist. Wer also Live-
 * Nachrichten prüfen will, wartet zuerst, bis die nachgelieferten Ereignisse
 * beim Client angekommen sind, und sendet erst dann.
 *
 * Jeder Test und jede Wiederholung bekommt einen eigenen Raum. Die Tests
 * teilen sich denselben Spring-Kontext und damit dieselben RoomChannels.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class ReconnectIntegrationTest {

    /** So lange warten wir höchstens auf etwas, das im Hintergrund passiert. */
    private static final long MAX_WAIT_MILLIS = 20_000;

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
    private ObjectMapper objectMapper;

    /**
     * S7: Der Client trennt nach einer Nachricht, verpasst 50 und verbindet
     * sich mit Last-Event-ID neu. Er bekommt genau die 50 in Reihenfolge,
     * keine doppelt, und danach wieder live.
     */
    @Test
    void catchesUpExactlyTheMissedMessagesLikeS7() throws Exception {
        UUID roomId = UUID.randomUUID();

        // Verbinden, eine Nachricht empfangen, ihre ID merken, trennen.
        String lastSeenId;
        try (SseTestClient firstClient = connect(roomId, null)) {
            waitForSubscribers(roomId, 1);
            sendMessage(roomId, "sub-s7-vorher", "Vorher");
            firstClient.waitForEvents(1, MAX_WAIT_MILLIS);
            List<String> firstIds = firstClient.ids();
            assertEquals(1, firstIds.size());
            lastSeenId = firstIds.get(0);
        }

        // 50 Nachrichten, während der Client weg ist. Gewartet wird auf das
        // Ereignis (alle 51 im Raum), nicht auf die 10 Sekunden aus S7.
        sendMessages(roomId, "sub-s7", 50);
        waitForEventsInRoom(roomId, 51);

        try (SseTestClient secondClient = connect(roomId, lastSeenId)) {
            // Erst wenn die 50 da sind, ist der Client eingetragen.
            secondClient.waitForEvents(50, MAX_WAIT_MILLIS);
            sendMessage(roomId, "live", "Wieder live");
            secondClient.waitForEvents(51, MAX_WAIT_MILLIS);
            Thread.sleep(500);

            List<String> ids = secondClient.ids();
            assertEquals(51, ids.size());
            Set<String> distinctIds = new HashSet<>(ids);
            assertEquals(51, distinctIds.size(), "Ein Ereignis kam doppelt");

            List<String> s7Contents = contentsOf(secondClient, "sub-s7");
            List<String> expected = numberedContents(1, 50);
            assertEquals(expected, s7Contents);

            List<String> data = secondClient.data();
            String lastData = data.get(50);
            JsonNode lastMessage = objectMapper.readTree(lastData);
            String lastSender = lastMessage.get("senderId").asText();
            assertEquals("live", lastSender);
        }
    }

    /** Fall 1 in 3.6: Ohne Last-Event-ID gibt es nichts aus der Liste, nur live. */
    @Test
    void newClientWithoutLastEventIdGetsOnlyLiveMessages() throws Exception {
        UUID roomId = UUID.randomUUID();
        sendMessages(roomId, "alt", 3);
        waitForEventsInRoom(roomId, 3);

        try (SseTestClient client = connect(roomId, null)) {
            waitForSubscribers(roomId, 1);
            AcceptedResponse live = sendMessage(roomId, "live", "Neu");
            client.waitForEvents(1, MAX_WAIT_MILLIS);
            Thread.sleep(500);

            List<String> ids = client.ids();
            assertEquals(1, ids.size(), "Alte Nachrichten wurden mitgeschickt");
            String liveId = live.id().toString();
            assertEquals(liveId, ids.get(0));
        }
    }

    /** 3.6: Eine unbekannte Last-Event-ID ergibt die ganze Liste des Raums. */
    @Test
    void unknownLastEventIdGetsTheWholeList() throws Exception {
        UUID roomId = UUID.randomUUID();
        List<AcceptedResponse> sent = sendMessages(roomId, "alt", 3);
        waitForEventsInRoom(roomId, 3);

        try (SseTestClient client = connect(roomId, "gibt-es-nicht")) {
            client.waitForEvents(3, MAX_WAIT_MILLIS);
            Thread.sleep(500);

            List<String> ids = client.ids();
            List<String> expectedIds = new ArrayList<>();
            for (AcceptedResponse accepted : sent) {
                String id = accepted.id().toString();
                expectedIds.add(id);
            }
            assertEquals(expectedIds, ids);
        }
    }

    /**
     * Die Sperre aus 3.6: Während ein zweiter Thread laufend sendet,
     * verbinden sich nacheinander zehn Clients mit Last-Event-ID neu. Jeder
     * muss genau die Nachrichten ab seiner ID bis «Nachricht 1000»
     * bekommen, lückenlos, in Reihenfolge, keine doppelt.
     *
     * Warum zehn Clients und je 450 nachzuholende Nachrichten: Ein Fehler in
     * der Sperre zeigt sich nur, wenn eine Live-Nachricht genau in die paar
     * Millisekunden fällt, in denen ein Client nachholt. Mit einem Client
     * und wenigen nachzuholenden Nachrichten blieb die Gegenprobe aus dem
     * Plan in 15 von 15 Läufen grün, mit einem Client und 450 noch in 13
     * von 15. Zehn Clients pro Lauf geben dem Fehler zehnmal so viele
     * Gelegenheiten.
     *
     * Fünfmal, weil sich ein Fehler in der Sperre nur manchmal zeigt. Jede
     * Wiederholung hat ihren eigenen Raum.
     */
    @RepeatedTest(5)
    void catchUpAndLiveDoNotOverlapWhileMessagesArrive() throws Exception {
        int totalMessages = 1000;
        int clientCount = 10;
        int firstConnectAt = 500;
        int connectEvery = 50;
        int catchUpSize = 450;

        UUID roomId = UUID.randomUUID();
        Thread sender = new Thread(() -> sendMessages(roomId, "race", totalMessages), "race-sender");
        sender.start();

        List<SseTestClient> clients = new ArrayList<>();
        List<Integer> lastSeenNumbers = new ArrayList<>();
        try {
            // Bei 500, 550, ... 950 Nachrichten im Raum je einen Client
            // verbinden, der die letzten 450 davon verpasst hat.
            for (int k = 0; k < clientCount; k++) {
                int connectAt = firstConnectAt + k * connectEvery;
                List<DeliveryEvent> eventsSoFar = waitForEventsInRoom(roomId, connectAt);
                int lastSeenNumber = connectAt - catchUpSize;
                DeliveryEvent lastSeen = eventsSoFar.get(lastSeenNumber - 1);
                String lastSeenId = lastSeen.id().toString();

                SseTestClient client = connect(roomId, lastSeenId);
                clients.add(client);
                lastSeenNumbers.add(lastSeenNumber);
            }

            sender.join(MAX_WAIT_MILLIS);

            for (int k = 0; k < clientCount; k++) {
                SseTestClient client = clients.get(k);
                int lastSeenNumber = lastSeenNumbers.get(k);
                int expectedCount = totalMessages - lastSeenNumber;
                client.waitForEvents(expectedCount, MAX_WAIT_MILLIS);
            }
            Thread.sleep(500);

            for (int k = 0; k < clientCount; k++) {
                SseTestClient client = clients.get(k);
                int lastSeenNumber = lastSeenNumbers.get(k);

                List<String> ids = client.ids();
                Set<String> distinctIds = new HashSet<>(ids);
                assertEquals(ids.size(), distinctIds.size(), "Client " + k + ": ein Ereignis kam doppelt");

                List<String> contents = contentsOf(client, "race");
                List<String> expected = numberedContents(lastSeenNumber + 1, totalMessages);
                assertEquals(expected, contents, "Client " + k + " ab Nachricht " + lastSeenNumber);
            }
        } finally {
            for (SseTestClient client : clients) {
                client.close();
            }
        }
    }

    /** Verbindet einen Test-Client mit dem Stream eines Raums. */
    private SseTestClient connect(UUID roomId, String lastEventId) {
        String url = "http://localhost:" + port + "/stream?roomId=" + roomId;
        return new SseTestClient(url, lastEventId);
    }

    /**
     * Schickt count Nachrichten mit dem Inhalt «Nachricht 1» bis
     * «Nachricht count», eine nach der anderen.
     */
    private List<AcceptedResponse> sendMessages(UUID roomId, String senderId, int count) {
        List<AcceptedResponse> sent = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            AcceptedResponse accepted = sendMessage(roomId, senderId, "Nachricht " + i);
            sent.add(accepted);
        }
        return sent;
    }

    /** Schickt eine Nachricht über POST /messages, wie es ein echter Client tut. */
    private AcceptedResponse sendMessage(UUID roomId, String senderId, String content) {
        SendMessageRequest request = new SendMessageRequest(roomId, senderId, "Test", content);
        ResponseEntity<AcceptedResponse> response =
                restTemplate.postForEntity("/messages", request, AcceptedResponse.class);
        assertEquals(202, response.getStatusCode().value());
        return response.getBody();
    }

    /**
     * Wartet, bis mindestens so viele Ereignisse im RoomChannel stehen, und
     * gibt sie zurück. Eine unbekannte ID liefert laut 3.6 die ganze Liste.
     */
    private List<DeliveryEvent> waitForEventsInRoom(UUID roomId, int expectedCount) throws InterruptedException {
        RoomChannel channel = roomChannels.channelFor(roomId);
        String unknownId = "unbekannt";
        long deadline = System.currentTimeMillis() + MAX_WAIT_MILLIS;
        List<DeliveryEvent> events = channel.eventsAfter(unknownId);
        while (events.size() < expectedCount && System.currentTimeMillis() < deadline) {
            Thread.sleep(20);
            events = channel.eventsAfter(unknownId);
        }
        assertTrue(events.size() >= expectedCount, "Zu wenige Ereignisse im Raum: " + events.size());
        return events;
    }

    /**
     * Wartet, bis der Server so viele Clients im Raum eingetragen hat. Nur
     * für Clients ohne Last-Event-ID: Ohne Nachliefern gibt es sonst kein
     * Zeichen, dass der Client eingetragen ist.
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

    /** «Nachricht from» bis «Nachricht to», so wie sendMessages sie verschickt. */
    private List<String> numberedContents(int from, int to) {
        List<String> contents = new ArrayList<>();
        for (int i = from; i <= to; i++) {
            contents.add("Nachricht " + i);
        }
        return contents;
    }
}
