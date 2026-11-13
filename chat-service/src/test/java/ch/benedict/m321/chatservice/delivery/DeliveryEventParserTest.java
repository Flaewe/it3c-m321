package ch.benedict.m321.chatservice.delivery;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Prüft den Vertrag aus Spezifikation 1.1, ganz ohne Broker: Nur id und
 * roomId werden gelesen, das JSON bleibt Zeichen für Zeichen gleich.
 */
class DeliveryEventParserTest {

    /** Ein einfacher ObjectMapper genügt. Wir lesen nur zwei UUIDs. */
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DeliveryEventParser parser = new DeliveryEventParser(objectMapper);

    /** Genau das Beispiel aus Spezifikation 1.2. */
    @Test
    void readsIdsAndKeepsTheJsonOfTheSpecification() {
        String json = "{\"id\":\"0b3c2f5a-1111-4a2b-8c3d-4e5f60718293\","
                + "\"roomId\":\"9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061\","
                + "\"senderId\":\"keycloak-sub-123\","
                + "\"senderName\":\"Alice Muster\","
                + "\"content\":\"Hallo Welt\","
                + "\"sentAt\":\"2026-09-25T10:15:30.123456Z\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        DeliveryEvent event = parser.parse(body);

        UUID expectedId = UUID.fromString("0b3c2f5a-1111-4a2b-8c3d-4e5f60718293");
        UUID expectedRoomId = UUID.fromString("9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061");
        assertEquals(expectedId, event.id());
        assertEquals(expectedRoomId, event.roomId());
        assertEquals(json, event.json());
    }

    /**
     * Andere Feldreihenfolge, zusätzliche Leerzeichen, ein unbekanntes Feld.
     * Würde der Parser das JSON neu schreiben, sähe es danach anders aus.
     * Dass es gleich bleibt, beweist: Es wird nicht neu serialisiert.
     */
    @Test
    void keepsTheJsonExactlyAsItCame() {
        String json = "{ \"content\" : \"Hallo\",   \"roomId\":\"9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061\","
                + " \"unknownField\": 42 , \"id\" : \"0b3c2f5a-1111-4a2b-8c3d-4e5f60718293\" }";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        DeliveryEvent event = parser.parse(body);

        assertEquals(json, event.json());
    }

    @Test
    void rejectsBrokenJson() {
        String json = "{\"id\": \"0b3c2f5a-";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> parser.parse(body));
    }

    @Test
    void rejectsJsonWithoutRoomId() {
        String json = "{\"id\":\"0b3c2f5a-1111-4a2b-8c3d-4e5f60718293\",\"content\":\"Hallo\"}";
        byte[] body = json.getBytes(StandardCharsets.UTF_8);

        assertThrows(IllegalArgumentException.class, () -> parser.parse(body));
    }
}
