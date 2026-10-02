package ch.benedict.m321.batchwriter.repository;

import ch.benedict.m321.batchwriter.dto.IncomingMessage;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Der einzige Weg in die Tabelle message.
 *
 * <p><b>Warum JdbcTemplate und nicht JPA.</b> Der ganze Dienst existiert,
 * um aus 500 Nachrichten EINEN Schreibvorgang zu machen. Genau das macht
 * batchUpdate. Ein repository.save() pro Nachricht wäre bequemer zu
 * schreiben und würde die Architektur aus PLANUNG.md sinnlos machen.
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class MessageRepository {

    /**
     * Der Einfügebefehl für alle Nachrichten eines Stapels.
     *
     * <p>ON CONFLICT (id) DO NOTHING ist die Duplikat-Abwehr. Kommt dieselbe
     * Nachricht ein zweites Mal, verwirft die Datenbank sie still — es
     * entsteht keine zweite Zeile und kein Fehler.
     *
     * <p>Die Entscheidung trifft bewusst die Datenbank und nicht der Dienst.
     * Ein vorheriges SELECT wäre eine zweite Abfrage, und bei zwei
     * gleichzeitig laufenden Instanzen wäre es trotzdem unsicher: zwischen
     * dem SELECT der einen und dem INSERT der anderen liegt immer ein
     * Moment.
     */
    private static final String INSERT_SQL = """
            INSERT INTO message (id, room_id, sender_id, sender_name, content, sent_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (id) DO NOTHING
            """;

    private final JdbcTemplate jdbcTemplate;

    /**
     * Schreibt einen ganzen Stapel mit einem einzigen Datenbankbefehl.
     *
     * Der Aufruf läuft in EINER Transaktion. Entweder steht danach der
     * ganze Stapel in der Tabelle, oder gar nichts davon — und im zweiten
     * Fall wird nichts bestätigt, sodass RabbitMQ erneut zustellt.
     */
    public void saveAll(List<IncomingMessage> messages) {
        if (messages.isEmpty()) {
            return;
        }

        jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {

            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                IncomingMessage message = messages.get(index);
                statement.setObject(1, message.id());
                statement.setObject(2, message.roomId());
                statement.setString(3, message.senderId());
                statement.setString(4, message.senderName());
                statement.setString(5, message.content());

                // OffsetDateTime und nicht Timestamp: setTimestamp rechnet
                // den Wert ueber die Zeitzone der JVM um. Laeuft der
                // Container in einer anderen Zeitzone als die Datenbank,
                // verschiebt sich der Zeitpunkt stillschweigend. Ein
                // OffsetDateTime traegt seine Zeitzone selbst mit.
                OffsetDateTime gesendet = message.sentAt().atOffset(ZoneOffset.UTC);
                statement.setObject(6, gesendet);
            }

            @Override
            public int getBatchSize() {
                return messages.size();
            }
        });

        log.debug("Wrote batch of {} messages in one statement", messages.size());
    }
}
