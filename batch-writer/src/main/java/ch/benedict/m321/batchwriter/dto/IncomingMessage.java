package ch.benedict.m321.batchwriter.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Eine Nachricht, so wie sie auf chat.persist ankommt.
 *
 * Das ist bewusst eine EIGENE Klasse und keine geteilte mit dem
 * chat-service. Der Vertrag zwischen den Diensten ist das JSON, nicht eine
 * Java-Klasse — ein gemeinsames Modul würde beide Dienste aneinanderbinden
 * und jede Änderung am Feldnamen zu einer Änderung in zwei Diensten machen.
 *
 * Die Feldnamen stimmen mit denen im JSON überein (camelCase). Die Spalten
 * der Tabelle heissen anders (snake_case) — die Zuordnung macht das
 * Repository.
 */
public record IncomingMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
