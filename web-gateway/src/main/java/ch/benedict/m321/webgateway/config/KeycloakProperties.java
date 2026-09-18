package ch.benedict.m321.webgateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Die drei Keycloak-Adressen, die dieses Gateway kennen muss.
 *
 * Dass es drei sind und nicht eine, ist der Kern des Login-Problems in diesem
 * Projekt: derselbe Keycloak hat aus dem Docker-Netz einen anderen Namen als
 * aus dem Browser.
 *
 * @param internalUrl Wohin das Gateway die Anfragen weiterreicht. Der
 *                    Service-Name im Docker-Netz, vom Host nicht erreichbar.
 * @param jwkSetUri   Woher der öffentliche Schlüssel kommt, mit dem wir
 *                    Tokens prüfen. Ebenfalls der interne Weg — das ist ein
 *                    Aufruf von Dienst zu Dienst, kein Browser ist beteiligt.
 * @param issuerUri   Was im Token als Aussteller stehen MUSS. Das ist die
 *                    Adresse, die der Browser sieht. Stimmt sie nicht mit
 *                    KC_HOSTNAME überein, scheitert jeder Login — siehe
 *                    PLANUNG.md, offener Punkt 11.
 */
@ConfigurationProperties(prefix = "keycloak")
public record KeycloakProperties(
        String internalUrl,
        String jwkSetUri,
        String issuerUri) {
}
