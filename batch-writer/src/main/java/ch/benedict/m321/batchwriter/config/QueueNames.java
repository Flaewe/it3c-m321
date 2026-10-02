package ch.benedict.m321.batchwriter.config;

/**
 * Die Namen der Queues an genau EINER Stelle.
 *
 * Dieselben Namen stehen im chat-service in einer eigenen Klasse. Das ist
 * Absicht und keine Nachlässigkeit: ein gemeinsames Modul würde beide
 * Dienste aneinanderbinden. Was sie verbindet, ist der Name der Queue —
 * und der ist eine Zeichenkette, keine Java-Klasse.
 */
public final class QueueNames {

    /** Hier holt dieser Dienst seine Nachrichten ab. */
    public static final String PERSIST_QUEUE = "chat.persist";

    /** Hierhin kommt, was dauerhaft nicht verarbeitet werden kann. */
    public static final String DEAD_LETTER_QUEUE = "chat.dlq";

    /** Diese Klasse ist eine reine Namenssammlung und wird nie erzeugt. */
    private QueueNames() {
    }
}
