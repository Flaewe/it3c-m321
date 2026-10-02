package ch.benedict.m321.batchwriter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import ch.benedict.m321.batchwriter.config.QueueNames;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Der ganze Weg: Nachricht in die Queue legen, Zeile in der Tabelle
 * nachsehen.
 *
 * <p>Hier laufen zum ersten Mal beide Container gleichzeitig. Das ist
 * teuer, und deshalb steht dieser Test am Ende und nicht am Anfang: Parser
 * und Repository sind einzeln schon bewiesen, hier geht es nur noch um
 * das Zusammenspiel.
 *
 * <p>Die Nachrichten werden <b>ohne die Kopfzeile __TypeId__</b> eingelegt —
 * genau so, wie es Szenario S5 beschreibt. Dass das funktioniert, ist der
 * eigentliche Punkt dieses Tests.
 *
 * <p><b>Jeder Test schreibt in seinen eigenen Raum</b> und zählt nur dort.
 * Alle Tests dieser Klasse teilen sich Queue und Tabelle. Kommt eine
 * Nachricht aus dem vorherigen Test verspätet an, landet sie sonst in der
 * Zählung des nächsten — und der wartet dann vergeblich auf eine Zahl, die
 * nie mehr stimmt.
 */
@SpringBootTest
@Testcontainers
class MessageBatchListenerIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    @ServiceConnection
    static RabbitMQContainer rabbitmq = new RabbitMQContainer("rabbitmq:3.13-management");

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void leereTabelle() {
        jdbcTemplate.execute("TRUNCATE TABLE message");
    }

    /**
     * Szenario S3: 1000 Nachrichten kommen an und stehen danach alle in
     * der Tabelle.
     */
    @Test
    void storesThousandMessages() {
        UUID raum = UUID.randomUUID();
        for (int i = 0; i < 1000; i++) {
            sendeNachricht(UUID.randomUUID(), raum);
        }

        await().atMost(Duration.ofSeconds(60))
                .untilAsserted(() -> assertThat(zaehleZeilen(raum)).isEqualTo(1000));
    }

    /**
     * Szenario S5: dieselbe Nachricht zweimal, nur mit content_type als
     * Kopfzeile, ergibt genau eine Zeile.
     *
     * Dieser Test deckt beide Fallen auf einmal ab: die fehlende Kopfzeile
     * __TypeId__ und das Duplikat.
     */
    @Test
    void storesDuplicateOnlyOnce() {
        UUID raum = UUID.randomUUID();
        UUID kennung = UUID.randomUUID();

        sendeNachricht(kennung, raum);
        sendeNachricht(kennung, raum);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(zaehleZeilen(raum)).isEqualTo(1));
    }

    /**
     * Szenario S5, zweiter Teil: ein Duplikat gehört NICHT in die
     * Dead-Letter-Queue.
     *
     * Es ist kein Fehler, dieselbe Nachricht zweimal zu bekommen — das ist
     * bei At-least-once der Normalfall.
     */
    @Test
    void doesNotSendDuplicateToDeadLetterQueue() {
        UUID raum = UUID.randomUUID();
        UUID kennung = UUID.randomUUID();

        sendeNachricht(kennung, raum);
        sendeNachricht(kennung, raum);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(zaehleZeilen(raum)).isEqualTo(1));

        Message ausDerDlq = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE);
        assertThat(ausDerDlq).isNull();
    }

    /**
     * Eine kaputte Nachricht landet in der Dead-Letter-Queue, und die
     * guten daneben trotzdem in der Tabelle.
     */
    @Test
    void movesUnreadableMessageToDeadLetterQueue() {
        UUID raum = UUID.randomUUID();
        sendeRohenRumpf("das ist kein JSON".getBytes(StandardCharsets.UTF_8));
        sendeNachricht(UUID.randomUUID(), raum);

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(zaehleZeilen(raum)).isEqualTo(1));

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            Message ausDerDlq = rabbitTemplate.receive(QueueNames.DEAD_LETTER_QUEUE);
            assertThat(ausDerDlq).isNotNull();
        });
    }

    /**
     * Die Queue ist nach getaner Arbeit leer.
     */
    @Test
    void leavesQueueEmpty() {
        UUID raum = UUID.randomUUID();
        for (int i = 0; i < 50; i++) {
            sendeNachricht(UUID.randomUUID(), raum);
        }

        await().atMost(Duration.ofSeconds(30))
                .untilAsserted(() -> assertThat(zaehleZeilen(raum)).isEqualTo(50));

        Message rest = rabbitTemplate.receive(QueueNames.PERSIST_QUEUE);
        assertThat(rest).isNull();
    }

    /** Zählt die Zeilen, die zu einem Raum gehören. */
    private int zaehleZeilen(UUID raum) {
        String abfrage = "SELECT count(*) FROM message WHERE room_id = ?";
        Integer anzahl = jdbcTemplate.queryForObject(abfrage, Integer.class, raum);
        return anzahl == null ? 0 : anzahl;
    }

    /**
     * Legt eine gültige Nachricht in die Queue — ohne __TypeId__.
     *
     * Genau so, wie das Prüfskript es laut Szenario S5 tut.
     */
    private void sendeNachricht(UUID kennung, UUID raum) {
        String json = """
                {"id":"%s","roomId":"%s",\
                "senderId":"keycloak-sub-123","senderName":"Alice Muster",\
                "content":"Hallo Welt","sentAt":"2026-09-25T10:15:30.123456Z"}"""
                .formatted(kennung, raum);

        sendeRohenRumpf(json.getBytes(StandardCharsets.UTF_8));
    }

    /** Legt beliebige Bytes als Nachricht in die Queue. */
    private void sendeRohenRumpf(byte[] rumpf) {
        MessageProperties eigenschaften = new MessageProperties();
        eigenschaften.setContentType(MessageProperties.CONTENT_TYPE_JSON);

        Message nachricht = new Message(rumpf, eigenschaften);
        rabbitTemplate.send("", QueueNames.PERSIST_QUEUE, nachricht);
    }
}
