package ch.benedict.m321.batchwriter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.benedict.m321.batchwriter.dto.IncomingMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;

/**
 * Prüft den Vertrag aus Spezifikation 2.2 gegen echtes JSON.
 *
 * Dieser Test braucht weder Broker noch Datenbank. Das ist Absicht: der
 * Vertrag ist die riskanteste Annahme des ganzen Dienstes, und je billiger
 * er zu prüfen ist, desto öfter läuft die Prüfung.
 *
 * @JsonTest gibt uns denselben ObjectMapper, den Spring Boot später im
 * Betrieb benutzt — ein selbst gebauter würde womöglich andere
 * Einstellungen haben und dann das Falsche beweisen.
 */
@JsonTest
class MessageParserTest {

    /**
     * Genau das JSON, das der chat-service erzeugt. Nachgemessen, nicht
     * ausgedacht — siehe Spezifikation 2.1.
     */
    private static final String ECHTE_NACHRICHT = """
            {"id":"0b3c2f5a-1111-4a2b-8c3d-4e5f60718293",\
            "roomId":"9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061",\
            "senderId":"keycloak-sub-123",\
            "senderName":"Alice Muster",\
            "content":"Hallo Welt",\
            "sentAt":"2026-09-25T10:15:30.123456Z"}""";

    @Autowired
    private ObjectMapper objectMapper;

    private MessageParser parser;

    @BeforeEach
    void erzeugeParser() {
        parser = new MessageParser(objectMapper);
    }

    /**
     * Der Normalfall: alle sechs Felder kommen richtig an.
     */
    @Test
    void readsAllSixFields() {
        IncomingMessage message = parser.parse(ECHTE_NACHRICHT.getBytes(StandardCharsets.UTF_8));

        assertThat(message.id()).isEqualTo(UUID.fromString("0b3c2f5a-1111-4a2b-8c3d-4e5f60718293"));
        assertThat(message.roomId()).isEqualTo(UUID.fromString("9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061"));
        assertThat(message.senderId()).isEqualTo("keycloak-sub-123");
        assertThat(message.senderName()).isEqualTo("Alice Muster");
        assertThat(message.content()).isEqualTo("Hallo Welt");
    }

    /**
     * Die Mikrosekunden dürfen nicht verlorengehen.
     *
     * Der chat-service schickt sechs Nachkommastellen. Wer nur auf
     * Millisekunden rundet, bekommt bei 1'667 Nachrichten pro Sekunde
     * reihenweise gleiche Zeitstempel — und der Client, der nach sent_at
     * sortiert, hat keine Reihenfolge mehr.
     */
    @Test
    void keepsMicrosecondPrecision() {
        IncomingMessage message = parser.parse(ECHTE_NACHRICHT.getBytes(StandardCharsets.UTF_8));

        Instant erwartet = Instant.parse("2026-09-25T10:15:30.123456Z");
        assertThat(message.sentAt()).isEqualTo(erwartet);
    }

    /**
     * Ein zusätzliches Feld darf nicht stören.
     *
     * Sonst könnte der chat-service nie ein Feld ergänzen, ohne dass der
     * batch-writer im selben Moment mitgeändert werden muss. Genau diese
     * Kopplung wollen wir in einer Microservice-Architektur nicht.
     */
    @Test
    void ignoresUnknownFields() {
        String mitZusatz = """
                {"id":"0b3c2f5a-1111-4a2b-8c3d-4e5f60718293",\
                "roomId":"9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061",\
                "senderId":"sub-1","senderName":"Alice","content":"Hi",\
                "sentAt":"2026-09-25T10:15:30.123456Z",\
                "editedAt":"2026-09-25T11:00:00Z"}""";

        IncomingMessage message = parser.parse(mitZusatz.getBytes(StandardCharsets.UTF_8));

        assertThat(message.content()).isEqualTo("Hi");
    }

    /**
     * Kaputtes JSON ist ein dauerhafter Fehler.
     */
    @Test
    void rejectsBrokenJson() {
        byte[] kaputt = "das ist kein JSON".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> parser.parse(kaputt))
                .isInstanceOf(MessageParseException.class);
    }

    /**
     * Ein fehlendes Pflichtfeld ebenfalls — und zwar hier und nicht erst
     * in der Datenbank.
     */
    @Test
    void rejectsMessageWithoutContent() {
        String ohneInhalt = """
                {"id":"0b3c2f5a-1111-4a2b-8c3d-4e5f60718293",\
                "roomId":"9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061",\
                "senderId":"sub-1","senderName":"Alice",\
                "sentAt":"2026-09-25T10:15:30.123456Z"}""";

        assertThatThrownBy(() -> parser.parse(ohneInhalt.getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(MessageParseException.class)
                .hasMessageContaining("content");
    }

    /**
     * Eine Nachricht ohne Kopfzeilen wird genauso gelesen.
     *
     * Dieser Test hält fest, was der Parser NICHT tut: er schaut sich
     * __TypeId__ gar nicht erst an. Deshalb bekommt er hier auch nur den
     * Rumpf zu sehen — mehr braucht er nie.
     */
    @Test
    void doesNotNeedAnyHeaders() {
        IncomingMessage message = parser.parse(ECHTE_NACHRICHT.getBytes(StandardCharsets.UTF_8));

        assertThat(message.id()).isNotNull();
    }
}
