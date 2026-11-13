package ch.benedict.m321.chatservice.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Die fertige Nachricht, wie sie in beide Queues geht.
 *
 * Diese Form ist der Vertrag zwischen den Diensten — aber der Vertrag ist
 * das JSON, nicht diese Klasse. Der batch-writer hat seine eigene Kopie.
 * Der Live-Weg liegt im chat-service selbst und braucht diese Klasse gar
 * nicht: Er liest aus dem JSON nur id und roomId (DeliveryIds) und gibt
 * den Rest unverändert weiter. Ein gemeinsames Modul würde alle Dienste
 * aneinanderbinden, und genau das wollen wir nicht zeigen.
 */
public record ChatMessage(
        UUID id,
        UUID roomId,
        String senderId,
        String senderName,
        String content,
        Instant sentAt) {
}
