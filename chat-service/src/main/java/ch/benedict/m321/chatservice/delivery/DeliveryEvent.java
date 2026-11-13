package ch.benedict.m321.chatservice.delivery;

import java.util.UUID;

/**
 * Ein Ereignis für den Live-Stream, so wie es vom Exchange kam.
 *
 * Das JSON bleibt der unveränderte Text der Nachricht. Wir zerlegen es
 * nicht in eine Java-Klasse, weil der Vertrag das JSON ist (Spezifikation
 * 1.1): Was der chat-service veröffentlicht hat, geht Zeichen für Zeichen
 * so an die Clients hinaus. Herausgezogen werden nur die zwei Werte, die
 * der Live-Weg selbst braucht: die ID für die id:-Zeile und für das
 * Nachholen, die Raum-ID für den Raumfilter.
 */
public record DeliveryEvent(UUID id, UUID roomId, String json) {
}
