package ch.benedict.m321.batchwriter.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ch.benedict.m321.batchwriter.config.BatchWriterProperties;
import ch.benedict.m321.batchwriter.dto.IncomingMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.json.JsonTest;
import org.springframework.dao.DataAccessResourceFailureException;

/**
 * Szenario S7: die Datenbank ist kurz weg.
 *
 * <p>Dieser Test kommt bewusst ohne Container aus. Einen echten
 * Datenbank-Container mitten im Test anzuhalten und wieder zu starten wäre
 * näher an der Wirklichkeit, aber das Ergebnis hinge an Zeitüberschreitungen
 * des Verbindungspools und wäre damit von Lauf zu Lauf verschieden. Ein
 * Test, der manchmal fehlschlägt, ist schlimmer als keiner: er bringt den
 * ganzen Durchgang zu Fall, ohne dass etwas kaputt ist.
 *
 * <p>Geprüft wird deshalb genau die Entscheidung, auf die es ankommt, und
 * zwar mit einem Repository, das auf Befehl scheitert: Was tut der Listener,
 * wenn das Schreiben misslingt? Dass der Dienst danach im echten Stack auch
 * wirklich weiterläuft, steht als Abnahmekriterium A10 in der Spezifikation
 * und wird am laufenden System gemessen.
 */
@JsonTest
class DatabaseOutageTest {

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * Die wichtigste Zusicherung: der Stapel geht ZURÜCK in die Queue und
     * nicht in die Dead-Letter-Queue.
     *
     * Würde hier eine AmqpRejectAndDontRequeueException geworfen, wären bei
     * jedem kurzen Datenbankausfall alle Nachrichten im Abstellgleis — und
     * zwar endgültig.
     */
    @Test
    void returnsBatchToQueueWhenDatabaseIsDown() {
        ScheiterndesRepository repository = new ScheiterndesRepository(1);
        MessageBatchListener listener = baueListener(repository);

        assertThatThrownBy(() -> listener.receiveBatch(List.of(baueAmqpNachricht())))
                .isInstanceOf(MessageBatchListener.PersistenceUnavailableException.class)
                .isNotInstanceOf(AmqpRejectAndDontRequeueException.class);
    }

    /**
     * Nach dem erneuten Zustellen gelingt das Schreiben.
     *
     * Das ist der zweite Teil von S7: der Dienst erholt sich von selbst,
     * ohne dass jemand ihn von Hand neu startet.
     */
    @Test
    void writesBatchOnRedeliveryAfterDatabaseIsBack() {
        ScheiterndesRepository repository = new ScheiterndesRepository(1);
        MessageBatchListener listener = baueListener(repository);
        Message nachricht = baueAmqpNachricht();

        // Erster Versuch: Datenbank weg, Stapel zurueck in die Queue.
        assertThatThrownBy(() -> listener.receiveBatch(List.of(nachricht)))
                .isInstanceOf(MessageBatchListener.PersistenceUnavailableException.class);

        // RabbitMQ stellt erneut zu, die Datenbank antwortet wieder.
        listener.receiveBatch(List.of(nachricht));

        assertThat(repository.geschriebeneNachrichten).hasSize(1);
    }

    /**
     * Nichts geht verloren, auch wenn es mehrere Anläufe braucht.
     */
    @Test
    void losesNothingAcrossSeveralFailedAttempts() {
        ScheiterndesRepository repository = new ScheiterndesRepository(3);
        MessageBatchListener listener = baueListener(repository);
        List<Message> stapel = List.of(baueAmqpNachricht(), baueAmqpNachricht());

        for (int versuch = 0; versuch < 3; versuch++) {
            assertThatThrownBy(() -> listener.receiveBatch(stapel))
                    .isInstanceOf(MessageBatchListener.PersistenceUnavailableException.class);
        }
        listener.receiveBatch(stapel);

        assertThat(repository.geschriebeneNachrichten).hasSize(2);
    }

    /**
     * Baut den Listener mit einer sehr kurzen Pause.
     *
     * Im Betrieb sind es zwei Sekunden. Im Test wären die nur Wartezeit
     * ohne Erkenntnis, deshalb eine Millisekunde.
     */
    private MessageBatchListener baueListener(MessageRepository repository) {
        MessageParser parser = new MessageParser(objectMapper);
        BatchWriterProperties eigenschaften = new BatchWriterProperties(500, 200L, 1L);
        return new MessageBatchListener(parser, repository, new RabbitTemplate(), eigenschaften);
    }

    /** Eine gültige Nachricht im Format des chat-service. */
    private Message baueAmqpNachricht() {
        String json = """
                {"id":"%s","roomId":"9a8b7c6d-2222-4e3f-9a0b-1c2d3e4f5061",\
                "senderId":"sub-1","senderName":"Alice","content":"Hallo",\
                "sentAt":"2026-09-25T10:15:30.123456Z"}"""
                .formatted(java.util.UUID.randomUUID());

        MessageProperties eigenschaften = new MessageProperties();
        eigenschaften.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        return new Message(json.getBytes(StandardCharsets.UTF_8), eigenschaften);
    }

    /**
     * Ein Repository, das die ersten N Aufrufe scheitern lässt.
     *
     * Von Hand geschrieben statt mit einer Mock-Bibliothek: so steht in
     * fünf Zeilen da, was es tut, und man kann es im Code-Review vorlesen.
     */
    private static class ScheiterndesRepository extends MessageRepository {

        private final List<IncomingMessage> geschriebeneNachrichten = new ArrayList<>();
        private int verbleibendeFehlschlaege;

        ScheiterndesRepository(int fehlschlaege) {
            super(null);
            this.verbleibendeFehlschlaege = fehlschlaege;
        }

        @Override
        public void saveAll(List<IncomingMessage> messages) {
            if (verbleibendeFehlschlaege > 0) {
                verbleibendeFehlschlaege = verbleibendeFehlschlaege - 1;
                throw new DataAccessResourceFailureException("Datenbank ist im Test nicht erreichbar");
            }
            geschriebeneNachrichten.addAll(messages);
        }
    }
}
