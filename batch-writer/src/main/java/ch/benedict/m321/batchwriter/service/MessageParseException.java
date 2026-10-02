package ch.benedict.m321.batchwriter.service;

/**
 * Eine Nachricht lässt sich nicht lesen.
 *
 * Diese Ausnahme bedeutet ausdrücklich: ein erneuter Versuch wird NIE
 * gelingen. Der Rumpf ist kein gültiges JSON, oder ein Pflichtfeld fehlt.
 * Solche Nachrichten gehören in die Dead-Letter-Queue und nicht zurück in
 * die Queue — sonst drehen sie sich endlos im Kreis und blockieren alles
 * dahinter.
 *
 * Das Gegenstück ist ein Datenbankfehler: der ist vorübergehend, und dort
 * ist ein erneuter Versuch genau richtig.
 */
public class MessageParseException extends RuntimeException {

    public MessageParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
