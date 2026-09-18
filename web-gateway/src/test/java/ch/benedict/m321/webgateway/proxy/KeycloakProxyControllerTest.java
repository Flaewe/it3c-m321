package ch.benedict.m321.webgateway.proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Prüft den Weiterleitungsweg /auth/** an einer Keycloak-Attrappe.
 *
 * <p>Statt eines echten Keycloak läuft hier der kleine HTTP-Server, den die
 * Java-Standardbibliothek mitbringt. Das genügt vollkommen: getestet wird
 * nicht, ob Keycloak richtig antwortet, sondern ob <em>wir</em> die Antwort
 * unverändert durchreichen. Dafür braucht es kein Docker und keine halbe
 * Minute Startzeit.
 *
 * <p>Die vier Eigenschaften, an denen ein Login sonst scheitert, sind hier je
 * ein Testfall: Weiterleitung nicht selbst verfolgen, Set-Cookie behalten,
 * Fehlerstatus durchreichen und den Pfad samt Query unverändert lassen.
 */
@SpringBootTest
@AutoConfigureMockMvc
class KeycloakProxyControllerTest {

    /** Die Attrappe. Merkt sich, was bei ihr ankam. */
    private static HttpServer keycloakStub;

    /** Was die Attrappe als Nächstes antworten soll. */
    private static int naechsterStatus;
    private static String naechsterKoerper;
    private static final List<String> zusatzKopfzeilen = new ArrayList<>();

    /** Was bei der Attrappe angekommen ist. */
    private static String empfangenerPfad;
    private static String empfangeneMethode;
    private static String empfangenerKoerper;
    private static String empfangenesForwardedHost;

    @BeforeAll
    static void startStub() throws IOException {
        keycloakStub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        keycloakStub.createContext("/", KeycloakProxyControllerTest::antworten);
        keycloakStub.start();
    }

    @AfterAll
    static void stopStub() {
        keycloakStub.stop(0);
    }

    /**
     * Sagt dem Gateway, wo die Attrappe horcht.
     *
     * Der Port steht erst fest, wenn der Server läuft — deshalb geht das nicht
     * über die application.yml, sondern erst zur Laufzeit.
     */
    @DynamicPropertySource
    static void stubAdresse(DynamicPropertyRegistry registry) {
        registry.add("keycloak.internal-url",
                () -> "http://127.0.0.1:" + keycloakStub.getAddress().getPort());
    }

    private static void antworten(HttpExchange austausch) throws IOException {
        empfangenerPfad = austausch.getRequestURI().toString();
        empfangeneMethode = austausch.getRequestMethod();
        empfangenerKoerper = new String(austausch.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        empfangenesForwardedHost = austausch.getRequestHeaders().getFirst("X-Forwarded-Host");

        for (int i = 0; i < zusatzKopfzeilen.size(); i += 2) {
            String name = zusatzKopfzeilen.get(i);
            String wert = zusatzKopfzeilen.get(i + 1);
            austausch.getResponseHeaders().add(name, wert);
        }

        byte[] koerper = naechsterKoerper.getBytes(StandardCharsets.UTF_8);
        austausch.sendResponseHeaders(naechsterStatus, koerper.length);
        austausch.getResponseBody().write(koerper);
        austausch.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void setzeAntwortZurueck() {
        naechsterStatus = 200;
        naechsterKoerper = "ok";
        zusatzKopfzeilen.clear();
        empfangenerPfad = null;
        empfangeneMethode = null;
        empfangenerKoerper = null;
        empfangenesForwardedHost = null;
    }

    /**
     * Der Pfad muss unverändert ankommen — samt Query.
     *
     * Im Query stehen beim Login die kodierte Rücksprungadresse und der
     * PKCE-Wert. Wird dort auch nur ein Zeichen anders kodiert, weist Keycloak
     * den Aufruf zurück.
     */
    @Test
    void forwardsPathAndQueryUnchanged() throws Exception {
        mockMvc.perform(get("/auth/realms/chat/protocol/openid-connect/auth")
                        .queryParam("redirect_uri", "http://localhost:8080/")
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isOk());

        assertThat(empfangenerPfad).startsWith("/auth/realms/chat/protocol/openid-connect/auth");
        assertThat(empfangenerPfad).contains("redirect_uri=http://localhost:8080/");
        assertThat(empfangenerPfad).contains("code_challenge_method=S256");
    }

    /**
     * Eine Weiterleitung darf NICHT selbst verfolgt werden.
     *
     * Das ist die wichtigste Eigenschaft des Proxys. Der Authorization-Code-Flow
     * besteht aus einer Kette von Weiterleitungen, die der Browser gehen muss —
     * folgt unser Client ihnen selbst, landet beim Browser die letzte Seite mit
     * der falschen Adresse, und der Login bricht ab.
     */
    @Test
    void doesNotFollowRedirects() throws Exception {
        naechsterStatus = 302;
        naechsterKoerper = "";
        zusatzKopfzeilen.add("Location");
        zusatzKopfzeilen.add("http://localhost:8080/?code=abc123");

        mockMvc.perform(get("/auth/realms/chat/login"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "http://localhost:8080/?code=abc123"));
    }

    /**
     * Set-Cookie muss erhalten bleiben.
     *
     * Darin steht die Sitzung von Keycloak. Geht die Kopfzeile verloren, fragt
     * Keycloak beim nächsten Schritt erneut nach dem Passwort — ohne Fehler,
     * einfach im Kreis.
     */
    @Test
    void keepsSetCookieHeader() throws Exception {
        zusatzKopfzeilen.add("Set-Cookie");
        zusatzKopfzeilen.add("AUTH_SESSION_ID=xyz; Path=/auth/realms/chat; HttpOnly");

        mockMvc.perform(get("/auth/realms/chat/login-actions/authenticate"))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie",
                        "AUTH_SESSION_ID=xyz; Path=/auth/realms/chat; HttpOnly"));
    }

    /**
     * Ein Fehlerstatus von Keycloak ist eine gültige Antwort, kein Absturz.
     *
     * Meldet Keycloak "401 falsches Passwort", soll der Benutzer genau das
     * sehen. Ohne den eigenen Status-Prüfer würde Spring hier eine Ausnahme
     * werfen und das Gateway mit 500 antworten — die Fehlermeldung wäre weg
     * und der Fehler läge scheinbar bei uns.
     */
    @Test
    void passesErrorStatusThrough() throws Exception {
        naechsterStatus = 401;
        naechsterKoerper = "Invalid user credentials";

        mockMvc.perform(get("/auth/realms/chat/protocol/openid-connect/token"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Invalid user credentials"));
    }

    /**
     * Ein POST muss mitsamt seinem Inhalt ankommen.
     *
     * Das Anmeldeformular von Keycloak wird per POST abgeschickt. Verliert der
     * Proxy den Inhalt, kommt bei Keycloak eine leere Anmeldung an.
     */
    @Test
    void forwardsPostBody() throws Exception {
        mockMvc.perform(post("/auth/realms/chat/login-actions/authenticate")
                        .contentType("application/x-www-form-urlencoded")
                        .content("username=alice&password=schulung"))
                .andExpect(status().isOk());

        assertThat(empfangeneMethode).isEqualTo("POST");
        assertThat(empfangenerKoerper).isEqualTo("username=alice&password=schulung");
    }

    /**
     * Keycloak muss erfahren, unter welchem Namen der Browser gefragt hat.
     *
     * Das ist die Gegenseite von KC_PROXY_HEADERS=xforwarded in
     * docker-compose.yml.
     */
    @Test
    void addsForwardedHostHeader() throws Exception {
        mockMvc.perform(get("/auth/realms/chat/login").header("Host", "localhost:8080"))
                .andExpect(status().isOk());

        assertThat(empfangenesForwardedHost).isEqualTo("localhost:8080");
    }

    /**
     * Der Login-Weg darf kein Token verlangen.
     *
     * Sonst bräuchte man ein Token, um an ein Token zu kommen. Alle Tests oben
     * laufen ohne Anmeldung — dass sie nicht mit 401 enden, ist bereits der
     * Nachweis. Dieser Test sagt es noch einmal ausdrücklich.
     */
    @Test
    void authPathIsReachableWithoutToken() throws Exception {
        mockMvc.perform(get("/auth/realms/chat/.well-known/openid-configuration"))
                .andExpect(status().isOk());
    }
}
