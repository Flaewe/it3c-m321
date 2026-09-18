package ch.benedict.m321.webgateway.dto;

import java.util.List;

/**
 * Wer gerade angemeldet ist, so wie die Web-App es braucht.
 *
 * Wir geben bewusst nicht das ganze Token weiter. Ein JWT von Keycloak
 * enthält ein gutes Dutzend Felder, die niemanden im Browser etwas angehen —
 * und was hier nicht drinsteht, kann die Oberfläche auch nicht versehentlich
 * anzeigen.
 *
 * @param userId      die sub-Kennung aus Keycloak. Das ist die Kennung, die
 *                    später in message.sender_id landet.
 * @param username    der Anmeldename, zum Beispiel "alice".
 * @param displayName der ausgeschriebene Name, zum Beispiel "Alice Muster".
 * @param roles       die Realm-Rollen, zum Beispiel "user" oder "admin".
 */
public record CurrentUser(
        String userId,
        String username,
        String displayName,
        List<String> roles) {
}
