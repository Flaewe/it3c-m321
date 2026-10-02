package ch.benedict.m321.batchwriter;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des batch-writer.
 *
 * Dieser Dienst ist der einzige im System, der in die Datenbank schreibt.
 * Er hat keine Schnittstelle, die jemand aufrufen könnte — seine einzige
 * Eingabe ist die Queue chat.persist.
 *
 * Der Grund für seine Existenz steht in PLANUNG.md, Abschnitt 4.1: bei
 * 1'667 Nachrichten pro Sekunde würde eine Datenbank an der Anzahl der
 * Transaktionen ersticken, nicht an der Datenmenge. Dieser Dienst macht
 * aus 500 einzelnen Nachrichten einen einzigen Schreibvorgang.
 */
@SpringBootApplication
public class BatchWriterApplication {

    /** Startet den Dienst. Er wartet danach nur noch auf die Queue. */
    public static void main(String[] args) {
        SpringApplication.run(BatchWriterApplication.class, args);
    }
}
