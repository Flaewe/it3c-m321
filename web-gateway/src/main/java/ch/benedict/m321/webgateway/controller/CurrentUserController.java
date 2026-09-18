package ch.benedict.m321.webgateway.controller;

import ch.benedict.m321.webgateway.dto.CurrentUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sagt der Web-App, wer gerade angemeldet ist.
 *
 * Das ist der erste Endpunkt, der ein gültiges Token verlangt, und damit der
 * Beweis, dass der Login funktioniert: kommt hier ein Name zurück, hat die
 * ganze Kette vom Browser über Keycloak bis zur Signaturprüfung gestimmt.
 */
@RestController
@Slf4j
public class CurrentUserController {

    /** Unter diesem Feld legt Keycloak die Realm-Rollen im Token ab. */
    private static final String REALM_ACCESS_CLAIM = "realm_access";

    /**
     * Liest die interessanten Felder aus dem bereits geprüften Token.
     *
     * Das Token ist an dieser Stelle schon geprüft — sonst wäre die Anfrage
     * gar nicht bis hierher gekommen. Wir lesen also nur noch ab.
     */
    @GetMapping("/api/me")
    public CurrentUser currentUser(@AuthenticationPrincipal Jwt jwt) {
        String userId = jwt.getSubject();
        String username = jwt.getClaimAsString("preferred_username");
        String displayName = jwt.getClaimAsString("name");
        List<String> roles = readRealmRoles(jwt);

        log.debug("Current user requested: {} with roles {}", username, roles);

        return new CurrentUser(userId, username, displayName, roles);
    }

    /**
     * Holt die Realm-Rollen aus dem Token.
     *
     * Keycloak verschachtelt sie: unter "realm_access" liegt eine Map, darin
     * eine Liste unter "roles". Beide Ebenen können fehlen — bei einem Token
     * aus einem Realm ohne Rollen zum Beispiel. Deshalb hier lieber eine
     * Handvoll if-Abfragen als ein eleganter Einzeiler, der bei null fliegt.
     */
    private List<String> readRealmRoles(Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS_CLAIM);
        if (realmAccess == null) {
            return List.of();
        }

        Object rawRoles = realmAccess.get("roles");
        if (!(rawRoles instanceof List<?> roleList)) {
            return List.of();
        }

        List<String> roles = new ArrayList<>();
        for (Object role : roleList) {
            roles.add(role.toString());
        }
        return roles;
    }
}
