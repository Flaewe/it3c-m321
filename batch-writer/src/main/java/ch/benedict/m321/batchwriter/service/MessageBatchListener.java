package ch.benedict.m321.batchwriter.service;

import ch.benedict.m321.batchwriter.config.BatchWriterProperties;
import ch.benedict.m321.batchwriter.config.QueueNames;
import ch.benedict.m321.batchwriter.dto.IncomingMessage;
import ch.benedict.m321.batchwriter.repository.MessageRepository;
import java.util.ArrayList;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Holt Stapel von chat.persist und schreibt sie in die Datenbank.
 *
 * <p>Diese Klasse ist die Stelle, an der sich die Fehlerbehandlung des
 * ganzen Dienstes entscheidet. Sie unterscheidet zwei Sorten Fehler, und
 * diese Unterscheidung ist wichtiger als alles andere hier:
 *
 * <ul>
 *   <li><b>Dauerhaft</b> — die Nachricht selbst ist kaputt. Ein erneuter
 *       Versuch wird nie gelingen, also ab in die Dead-Letter-Queue.</li>
 *   <li><b>Vorübergehend</b> — die Datenbank ist gerade weg. Die Nachricht
 *       ist in Ordnung, nur die Umgebung nicht. Also zurück in die Queue
 *       und später nochmal.</li>
 * </ul>
 *
 * <p>Wer beides gleich behandelt, bekommt entweder eine Endlosschleife mit
 * einer kaputten Nachricht, oder er verliert alles bei einem
 * Datenbankausfall von zehn Sekunden.
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class MessageBatchListener {

    /** Der leere Exchange liefert direkt an die Queue mit diesem Namen. */
    private static final String DIRECT_TO_QUEUE = "";

    private final MessageParser messageParser;
    private final MessageRepository messageRepository;
    private final RabbitTemplate rabbitTemplate;
    private final BatchWriterProperties properties;

    /**
     * Nimmt einen ganzen Stapel entgegen.
     *
     * <p>Der Parameter ist bewusst eine Liste von AMQP-Nachrichten und
     * nicht von fertigen Objekten. Nur so kommen wir an den rohen Rumpf
     * und können die Kopfzeile __TypeId__ ignorieren — siehe
     * {@link MessageParser}.
     *
     * <p>Kehrt diese Methode normal zurück, bestätigt Spring den ganzen
     * Stapel. Wirft sie, wird nichts bestätigt.
     */
    @RabbitListener(queues = QueueNames.PERSIST_QUEUE)
    public void receiveBatch(List<Message> batch) {
        log.debug("Received batch of {} messages", batch.size());

        List<IncomingMessage> lesbareNachrichten = leseAlleLesbaren(batch);
        schreibeOderStelleZurueck(lesbareNachrichten, batch.size());
    }

    /**
     * Wandelt um, was sich umwandeln lässt, und sondert den Rest aus.
     *
     * <p>Eine kaputte Nachricht darf 499 gute nicht aufhalten. Sie wandert
     * deshalb einzeln in die Dead-Letter-Queue, und der Stapel läuft ohne
     * sie weiter.
     *
     * <p>Das Verschieben passiert hier von Hand und nicht über die
     * Dead-Letter-Einstellung der Queue. Grund: im Stapelbetrieb bestätigt
     * oder verwirft Spring immer den ganzen Stapel — eine einzelne
     * Nachricht lässt sich gar nicht gezielt ablehnen. Von Hand gesendet
     * geht sie trotzdem genau dorthin, wo sie hingehört.
     */
    private List<IncomingMessage> leseAlleLesbaren(List<Message> batch) {
        List<IncomingMessage> lesbare = new ArrayList<>();

        for (Message amqpNachricht : batch) {
            byte[] rumpf = amqpNachricht.getBody();
            try {
                IncomingMessage nachricht = messageParser.parse(rumpf);
                lesbare.add(nachricht);
            } catch (MessageParseException problem) {
                log.error("Unreadable message moved to {}: {}",
                        QueueNames.DEAD_LETTER_QUEUE, problem.getMessage());
                rabbitTemplate.send(DIRECT_TO_QUEUE, QueueNames.DEAD_LETTER_QUEUE, amqpNachricht);
            }
        }

        return lesbare;
    }

    /**
     * Schreibt den Stapel — oder stellt ihn zurück, wenn die Datenbank weg ist.
     *
     * <p>Die Pause vor dem Zurückstellen ist kein Schönheitsfehler. Ohne
     * sie würde RabbitMQ den Stapel sofort erneut zustellen, der Versuch
     * sofort wieder scheitern, und der Dienst würde einen Prozessorkern mit
     * sinnlosen Versuchen belegen, während die Protokolldatei vollläuft.
     *
     * <p>Geworfen wird eine gewöhnliche Ausnahme und ausdrücklich KEINE
     * AmqpRejectAndDontRequeueException: der Stapel soll zurück in die
     * Queue und nicht in die Dead-Letter-Queue. Sobald die Datenbank
     * wieder antwortet, läuft er durch. Der Dienst stirbt dabei nicht und
     * muss nicht von Hand neu gestartet werden.
     */
    private void schreibeOderStelleZurueck(List<IncomingMessage> nachrichten, int stapelgroesse) {
        try {
            messageRepository.saveAll(nachrichten);
            log.info("Stored {} of {} messages", nachrichten.size(), stapelgroesse);
        } catch (DataAccessException datenbankProblem) {
            log.warn("Database unavailable, returning batch of {} to the queue: {}",
                    stapelgroesse, datenbankProblem.getMessage());
            warteVorDemNaechstenVersuch();
            throw new PersistenceUnavailableException(datenbankProblem);
        }
    }

    /**
     * Wartet die eingestellte Pause ab.
     *
     * Wird der Dienst währenddessen beendet, brechen wir die Pause ab und
     * stellen den Stapel sofort zurück — ein Container soll beim Stoppen
     * nicht unnötig hängen.
     */
    private void warteVorDemNaechstenVersuch() {
        try {
            Thread.sleep(properties.dbRetryPauseMs());
        } catch (InterruptedException unterbrochen) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Sagt Spring: diesen Stapel bitte zurück in die Queue.
     *
     * Eine eigene Klasse, damit in den Protokollen auf den ersten Blick
     * steht, worum es ging. Sie erbt ausdrücklich NICHT von
     * AmqpRejectAndDontRequeueException — genau darin liegt der
     * Unterschied zur kaputten Nachricht.
     */
    static class PersistenceUnavailableException extends RuntimeException {

        PersistenceUnavailableException(Throwable cause) {
            super("Database not reachable, batch returned to the queue", cause);
        }
    }
}
