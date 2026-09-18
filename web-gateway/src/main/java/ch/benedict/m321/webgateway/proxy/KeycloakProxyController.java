package ch.benedict.m321.webgateway.proxy;

import ch.benedict.m321.webgateway.config.KeycloakProperties;
import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

/**
 * Reicht alles unter /auth/** an Keycloak weiter.
 *
 * <p><b>Warum es diesen Umweg gibt.</b> Beim Login schickt Keycloak dem
 * Browser ein Anmeldeformular — der Browser muss Keycloak also erreichen
 * können. Er läuft aber auf dem Host, und Keycloak hat keinen offenen Port.
 * Die Vorgabe lautet: nur die Web-App ist über localhost erreichbar. Also
 * nimmt das Gateway die Anfrage entgegen und stellt sie im Docker-Netz neu.
 *
 * <p><b>Warum der Proxy dumm sein darf.</b> Er schreibt nichts um — keine
 * Adressen im Text, keine Weiterleitungsziele. Das funktioniert nur, weil
 * Keycloak über KC_HOSTNAME weiss, unter welcher Adresse der Browser ihn
 * sieht, und seine Links selbst richtig baut. Genau das ist offener Punkt 11:
 * stimmt KC_HOSTNAME nicht, schickt Keycloak den Browser auf eine Adresse,
 * die es vom Host aus nicht gibt.
 */
@RestController
@Slf4j
public class KeycloakProxyController {

    /**
     * Kopfzeilen, die nicht weitergereicht werden dürfen.
     *
     * Die ersten fünf verbietet der HTTP-Client der Java-Standardbibliothek —
     * er setzt sie selbst und wirft eine Ausnahme, wenn man es auch tut. Die
     * übrigen gelten nur für einen einzelnen Netzwerkabschnitt ("hop-by-hop")
     * und ergeben auf der neuen Verbindung keinen Sinn mehr.
     */
    private static final Set<String> HEADERS_NOT_FORWARDED = Set.of(
            "connection",
            "content-length",
            "expect",
            "host",
            "upgrade",
            "keep-alive",
            "transfer-encoding",
            "te",
            "trailer",
            "proxy-authenticate",
            "proxy-authorization");

    private final RestClient restClient;
    private final String keycloakInternalUrl;

    public KeycloakProxyController(RestClient keycloakRestClient, KeycloakProperties keycloakProperties) {
        this.restClient = keycloakRestClient;
        this.keycloakInternalUrl = keycloakProperties.internalUrl();
    }

    /**
     * Nimmt jede Anfrage unter /auth/** entgegen, egal mit welcher Methode.
     *
     * GET holt das Anmeldeformular, POST schickt Benutzername und Passwort ab —
     * beides muss durch dieselbe Tür. Deshalb steht hier keine Einschränkung
     * auf eine Methode.
     */
    @RequestMapping("/auth/**")
    public ResponseEntity<byte[]> forward(HttpServletRequest request) throws IOException {
        URI targetUrl = buildTargetUrl(request);
        HttpMethod method = HttpMethod.valueOf(request.getMethod());
        HttpHeaders headers = copyRequestHeaders(request);
        byte[] body = request.getInputStream().readAllBytes();

        log.debug("Proxying {} {} to Keycloak", method, targetUrl);

        RestClient.RequestBodySpec anfrage = restClient
                .method(method)
                .uri(targetUrl)
                .headers(ziel -> ziel.addAll(headers));

        if (body.length > 0) {
            anfrage = anfrage.body(body);
        }

        ResponseEntity<byte[]> antwort = anfrage.retrieve().toEntity(byte[].class);
        HttpHeaders antwortHeaders = copyResponseHeaders(antwort.getHeaders());

        return new ResponseEntity<>(antwort.getBody(), antwortHeaders, antwort.getStatusCode());
    }

    /**
     * Baut die Zieladresse im Docker-Netz.
     *
     * Der Pfad bleibt unverändert: der Browser fragt /auth/realms/chat/... an,
     * und Keycloak liefert wegen KC_HTTP_RELATIVE_PATH=/auth genau unter
     * demselben Pfad aus. Nur der Namensteil davor wird ausgetauscht.
     */
    private URI buildTargetUrl(HttpServletRequest request) {
        String path = request.getRequestURI();
        String query = request.getQueryString();

        String vollstaendig = keycloakInternalUrl + path;
        if (query != null) {
            vollstaendig = vollstaendig + "?" + query;
        }

        // URI.create statt der String-Variante von uri(): die String-Variante
        // behandelt die Adresse als Vorlage mit Platzhaltern und kodiert das
        // schon kodierte Query ein zweites Mal. Beim Login stehen dort
        // Adressen mit %3A und %2F drin — die wuerden dabei zerstoert.
        return URI.create(vollstaendig);
    }

    /**
     * Übernimmt die Kopfzeilen des Browsers und ergänzt die X-Forwarded-Angaben.
     *
     * Die Ergänzung ist nötig, weil Keycloak sonst glaubt, die Anfrage käme
     * direkt von uns aus dem Docker-Netz. Mit KC_PROXY_HEADERS=xforwarded
     * liest es diese drei Felder und weiss, wer wirklich gefragt hat.
     */
    private HttpHeaders copyRequestHeaders(HttpServletRequest request) {
        HttpHeaders headers = new HttpHeaders();

        var namen = request.getHeaderNames();
        while (namen.hasMoreElements()) {
            String name = namen.nextElement();
            if (HEADERS_NOT_FORWARDED.contains(name.toLowerCase())) {
                continue;
            }
            var werte = request.getHeaders(name);
            while (werte.hasMoreElements()) {
                headers.add(name, werte.nextElement());
            }
        }

        headers.set("X-Forwarded-Proto", request.getScheme());
        headers.set("X-Forwarded-For", request.getRemoteAddr());

        // Den Host-Header schickt jeder normale Browser mit. Fehlt er doch
        // einmal, lassen wir die Kopfzeile lieber weg als sie leer zu setzen:
        // ein leeres X-Forwarded-Host wuerde Keycloak eine Adresse ohne
        // Namen vorgaukeln.
        String host = request.getHeader("Host");
        if (host != null) {
            headers.set("X-Forwarded-Host", host);
        }

        return headers;
    }

    /**
     * Gibt die Antwort von Keycloak weiter, ohne sie anzufassen.
     *
     * Wichtig sind hier zwei Kopfzeilen, die man leicht übersieht: "Location"
     * enthält die Weiterleitung zurück zur Web-App, und "Set-Cookie" die
     * Sitzung von Keycloak. Fällt eine davon weg, bricht der Login ab, ohne
     * dass eine Fehlermeldung erscheint.
     *
     * Entfernt werden nur die Angaben zur Übertragung selbst — die gelten für
     * die Verbindung zu Keycloak und nicht für die zum Browser.
     */
    private HttpHeaders copyResponseHeaders(HttpHeaders original) {
        HttpHeaders headers = new HttpHeaders();

        for (Map.Entry<String, List<String>> eintrag : original.entrySet()) {
            String name = eintrag.getKey();
            if (HEADERS_NOT_FORWARDED.contains(name.toLowerCase())) {
                continue;
            }
            headers.addAll(name, eintrag.getValue());
        }

        return headers;
    }
}
