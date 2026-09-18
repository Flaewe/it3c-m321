package ch.benedict.m321.webgateway.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prüft den Endpunkt /api/me.
 *
 * Keycloak läuft in diesem Test nicht. Der Baustein jwt() aus spring-security-test
 * legt ein erfundenes, bereits geprüftes Token in den Sicherheitskontext — wir
 * testen also genau das, was uns hier gehört: ob wir die richtigen Felder
 * auslesen. Ob Keycloak ein solches Token wirklich so ausstellt, kann erst ein
 * Start des Gesamtsystems zeigen.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CurrentUserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void returnsNameAndRolesFromToken() throws Exception {
        Map<String, Object> realmAccess = Map.of("roles", List.of("user", "admin"));

        mockMvc.perform(get("/api/me").with(jwt().jwt(token -> {
                    token.subject("a1b2c3");
                    token.claim("preferred_username", "alice");
                    token.claim("name", "Alice Muster");
                    token.claim("realm_access", realmAccess);
                })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("a1b2c3"))
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.displayName").value("Alice Muster"))
                .andExpect(jsonPath("$.roles[0]").value("user"))
                .andExpect(jsonPath("$.roles[1]").value("admin"));
    }

    /**
     * Ein Token ohne Rollen darf keinen Fehler auslösen.
     *
     * Das ist kein erfundener Fall: ein Realm ohne zugewiesene Rollen liefert
     * gar kein realm_access-Feld, und dann steht dort null statt einer leeren
     * Liste.
     */
    @Test
    void returnsEmptyRolesWhenTokenHasNone() throws Exception {
        mockMvc.perform(get("/api/me").with(jwt().jwt(token -> {
                    token.subject("d4e5f6");
                    token.claim("preferred_username", "bob");
                })))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("bob"))
                .andExpect(jsonPath("$.roles").isEmpty());
    }

    /**
     * Ohne Token gibt es keine Auskunft.
     *
     * Das ist der eigentliche Zweck des Gateways: es ist der einzige
     * Wachposten, und hier lässt sich nachweisen, dass er wacht.
     */
    @Test
    void rejectsRequestWithoutToken() throws Exception {
        mockMvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }
}
