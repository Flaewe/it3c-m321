package ch.benedict.m321.batchwriter.repository;

import static org.assertj.core.api.Assertions.assertThat;

import ch.benedict.m321.batchwriter.dto.IncomingMessage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Prüft das Stapel-Schreiben gegen eine echte PostgreSQL.
 *
 * Eine Datenbank im Arbeitsspeicher würde hier nichts beweisen: ON CONFLICT
 * gibt es dort anders oder gar nicht, und die Zahl der Transaktionen lässt
 * sich nur bei einer echten Datenbank ablesen.
 */
@SpringBootTest
@Testcontainers
class MessageRepositoryIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void leereTabelle() {
        jdbcTemplate.execute("TRUNCATE TABLE message");
    }

    /**
     * Der Normalfall: 500 Nachrichten ergeben 500 Zeilen.
     */
    @Test
    void writesWholeBatch() {
        List<IncomingMessage> stapel = erzeugeNachrichten(500);

        messageRepository.saveAll(stapel);

        Integer anzahl = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        assertThat(anzahl).isEqualTo(500);
    }

    /**
     * Szenario S5: Dieselbe Nachricht zweimal ergibt EINE Zeile.
     *
     * Hier liegen beide Kopien im selben Stapel. Das ist der unangenehmere
     * Fall: ein vorheriges SELECT würde ihn nicht abfangen, weil beide
     * Einfügungen im selben Befehl stecken. ON CONFLICT fängt ihn ab.
     */
    @Test
    void writesDuplicateInSameBatchOnlyOnce() {
        IncomingMessage nachricht = erzeugeNachricht(UUID.randomUUID());
        List<IncomingMessage> stapel = new ArrayList<>();
        stapel.add(nachricht);
        stapel.add(nachricht);

        messageRepository.saveAll(stapel);

        Integer anzahl = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        assertThat(anzahl).isEqualTo(1);
    }

    /**
     * Szenario S5, zweite Form: dieselbe Nachricht in zwei getrennten
     * Stapeln. So sieht es aus, wenn RabbitMQ nach einem Absturz erneut
     * zustellt.
     */
    @Test
    void writesDuplicateInSeparateBatchesOnlyOnce() {
        IncomingMessage nachricht = erzeugeNachricht(UUID.randomUUID());

        messageRepository.saveAll(List.of(nachricht));
        messageRepository.saveAll(List.of(nachricht));

        Integer anzahl = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        assertThat(anzahl).isEqualTo(1);
    }

    /**
     * Ein Duplikat darf den Rest des Stapels nicht aufhalten.
     */
    @Test
    void keepsOtherMessagesWhenOneIsDuplicate() {
        IncomingMessage doppelt = erzeugeNachricht(UUID.randomUUID());
        messageRepository.saveAll(List.of(doppelt));

        List<IncomingMessage> stapel = new ArrayList<>();
        stapel.add(doppelt);
        stapel.add(erzeugeNachricht(UUID.randomUUID()));
        stapel.add(erzeugeNachricht(UUID.randomUUID()));

        messageRepository.saveAll(stapel);

        Integer anzahl = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        assertThat(anzahl).isEqualTo(3);
    }

    /**
     * Der Zeitpunkt muss unverändert zurückkommen, samt Mikrosekunden.
     *
     * Das prüft die Entscheidung aus dem Repository, OffsetDateTime statt
     * Timestamp zu setzen: ginge die Zeitzone über die JVM, käme hier ein
     * verschobener Wert heraus.
     */
    @Test
    void keepsExactInstant() {
        UUID kennung = UUID.randomUUID();
        Instant gesendet = Instant.parse("2026-09-25T10:15:30.123456Z");
        IncomingMessage nachricht = new IncomingMessage(
                kennung, UUID.randomUUID(), "sub-1", "Alice", "Hallo", gesendet);

        messageRepository.saveAll(List.of(nachricht));

        Instant gelesen = jdbcTemplate.queryForObject(
                "SELECT sent_at FROM message WHERE id = ?", Instant.class, kennung);
        assertThat(gelesen).isEqualTo(gesendet);
    }

    /**
     * Szenario S4: 1000 Nachrichten dürfen die Datenbank höchstens 100
     * Transaktionen kosten.
     *
     * Das ist der eigentliche Daseinsgrund des Dienstes, deshalb wird er
     * hier gemessen und nicht behauptet. Gezählt wird über
     * pg_stat_database — dieselbe Quelle, aus der auch das Prüfskript liest.
     */
    @Test
    void writesThousandMessagesInFewTransactions() {
        long vorher = leseTransaktionszaehler();

        messageRepository.saveAll(erzeugeNachrichten(500));
        messageRepository.saveAll(erzeugeNachrichten(500));

        long nachher = leseTransaktionszaehler();
        long verbraucht = nachher - vorher;

        Integer anzahl = jdbcTemplate.queryForObject("SELECT count(*) FROM message", Integer.class);
        assertThat(anzahl).isEqualTo(1000);
        assertThat(verbraucht).isLessThanOrEqualTo(100);
    }

    /**
     * Ein leerer Stapel darf nicht in die Datenbank gehen.
     *
     * Er kann vorkommen, wenn alle Nachrichten eines Stapels kaputt waren
     * und ausgesondert wurden. Ein INSERT ohne Zeilen wäre eine
     * Transaktion ohne Zweck.
     */
    @Test
    void doesNothingForEmptyBatch() {
        long vorher = leseTransaktionszaehler();

        messageRepository.saveAll(List.of());

        long nachher = leseTransaktionszaehler();
        assertThat(nachher - vorher).isLessThanOrEqualTo(1);
    }

    /** Liest, wie viele Transaktionen die Datenbank bisher bestätigt hat. */
    private long leseTransaktionszaehler() {
        String abfrage = "SELECT xact_commit FROM pg_stat_database WHERE datname = current_database()";
        Long wert = jdbcTemplate.queryForObject(abfrage, Long.class);
        return wert == null ? 0L : wert;
    }

    /** Baut eine Nachricht mit vorgegebener Kennung. */
    private IncomingMessage erzeugeNachricht(UUID kennung) {
        return new IncomingMessage(
                kennung,
                UUID.randomUUID(),
                "keycloak-sub-123",
                "Alice Muster",
                "Hallo Welt",
                Instant.parse("2026-09-25T10:15:30.123456Z"));
    }

    /** Baut eine Liste frischer Nachrichten. */
    private List<IncomingMessage> erzeugeNachrichten(int anzahl) {
        List<IncomingMessage> nachrichten = new ArrayList<>();
        for (int i = 0; i < anzahl; i++) {
            nachrichten.add(erzeugeNachricht(UUID.randomUUID()));
        }
        return nachrichten;
    }
}
