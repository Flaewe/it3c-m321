package ch.benedict.m321.batchwriter.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Die drei Zahlen, die das Verhalten des Dienstes bestimmen.
 *
 * @param batchSize      Wie viele Nachrichten höchstens in einen Stapel
 *                       gehen. 500 aus PLANUNG.md 4.1: damit werden aus
 *                       1'667 Nachrichten pro Sekunde rund 3,3
 *                       Schreibvorgänge.
 * @param batchTimeoutMs Nach wie vielen Millisekunden ein angefangener
 *                       Stapel trotzdem geschrieben wird. Ohne diese
 *                       Grenze bliebe die letzte Nachricht eines ruhigen
 *                       Abends bis zum nächsten Morgen liegen.
 * @param dbRetryPauseMs Wie lange nach einem Datenbankfehler gewartet
 *                       wird, bevor der Stapel zurückgestellt wird.
 */
@ConfigurationProperties(prefix = "batch-writer")
public record BatchWriterProperties(
        int batchSize,
        long batchTimeoutMs,
        long dbRetryPauseMs) {
}
