package ch.benedict.m321.chatservice.delivery;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.UUID;

/**
 * Die zwei Felder, die der Live-Weg aus einer Nachricht braucht.
 *
 * Absichtlich nicht die ganze ChatMessage: Der Live-Weg muss die Nachricht
 * nicht verstehen, nur zuordnen (Spezifikation 1.1). Die id wird zur
 * id:-Zeile und zum Merkmal beim Nachholen, die roomId entscheidet, welche
 * Clients die Nachricht bekommen.
 *
 * ignoreUnknown = true: Alle anderen Felder (senderName, content, ...)
 * werden beim Lesen übersprungen. Kommt später ein neues Feld dazu,
 * bricht hier nichts.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record DeliveryIds(UUID id, UUID roomId) {
}
