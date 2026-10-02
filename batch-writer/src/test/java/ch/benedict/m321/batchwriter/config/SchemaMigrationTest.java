package ch.benedict.m321.batchwriter.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft, dass Flyway beim Start das Schema anlegt.
 *
 * Der Test startet eine echte PostgreSQL in einem Container. Ein Test gegen
 * eine Datenbank im Arbeitsspeicher würde hier nichts beweisen: Spalten wie
 * timestamptz und ein Index mit DESC gibt es dort entweder gar nicht oder
 * anders.
 */
@SpringBootTest
@Testcontainers
class SchemaMigrationTest {

    /**
     * Dieselbe Hauptversion wie im Stack. Eine andere Version zu testen als
     * die, die später läuft, wäre eine Prüfung mit Beigeschmack.
     */
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * Die Tabelle message muss genau die sechs Spalten aus PLANUNG.md 3.7
     * haben — nicht mehr und nicht weniger.
     */
    @Test
    void createsMessageTableWithAllColumns() {
        String abfrage = "SELECT column_name FROM information_schema.columns "
                + "WHERE table_name = 'message' ORDER BY column_name";

        List<String> spalten = jdbcTemplate.queryForList(abfrage, String.class);

        assertThat(spalten).containsExactly(
                "content", "id", "room_id", "sender_id", "sender_name", "sent_at");
    }

    /**
     * Die Datentypen sind Teil des Vertrags mit der Datenbank.
     *
     * Besonders sent_at: timestamptz statt timestamp. Mit der falschen
     * Variante ginge die Zeitzone verloren, und das fiele erst auf, wenn
     * jemand in einer anderen Zeitzone sitzt.
     */
    @Test
    void usesExpectedColumnTypes() {
        String abfrage = "SELECT data_type FROM information_schema.columns "
                + "WHERE table_name = 'message' AND column_name = ?";

        String idTyp = jdbcTemplate.queryForObject(abfrage, String.class, "id");
        String sentAtTyp = jdbcTemplate.queryForObject(abfrage, String.class, "sent_at");
        String contentTyp = jdbcTemplate.queryForObject(abfrage, String.class, "content");

        assertThat(idTyp).isEqualTo("uuid");
        assertThat(sentAtTyp).isEqualTo("timestamp with time zone");
        assertThat(contentTyp).isEqualTo("text");
    }

    /**
     * id muss Primärschlüssel sein — darauf beruht die ganze
     * Duplikat-Abwehr aus Spezifikation 3.3.
     */
    @Test
    void usesIdAsPrimaryKey() {
        String abfrage = "SELECT a.attname FROM pg_index i "
                + "JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey) "
                + "WHERE i.indrelid = 'message'::regclass AND i.indisprimary";

        List<String> schluesselspalten = jdbcTemplate.queryForList(abfrage, String.class);

        assertThat(schluesselspalten).containsExactly("id");
    }

    /**
     * Der eine Index auf dem Lesepfad muss da sein.
     */
    @Test
    void createsIndexForHistoryQuery() {
        String abfrage = "SELECT indexname FROM pg_indexes WHERE tablename = 'message'";

        List<String> indizes = jdbcTemplate.queryForList(abfrage, String.class);

        assertThat(indizes).contains("idx_message_room_sent_at");
    }
}
