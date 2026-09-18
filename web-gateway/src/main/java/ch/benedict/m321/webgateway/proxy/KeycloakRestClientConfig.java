package ch.benedict.m321.webgateway.proxy;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Der HTTP-Client, mit dem das Gateway Keycloak anspricht.
 *
 * Das ist bewusst ein eigener Client und nicht der Standard-Client von Spring:
 * ein Proxy braucht zwei Eigenschaften, die man sonst nie will.
 */
@Configuration
public class KeycloakRestClientConfig {

    /**
     * Baut den Client für den Weg zu Keycloak.
     *
     * <p><b>Erstens: Weiterleitungen nicht selbst verfolgen.</b> Antwortet
     * Keycloak mit "302 geh nach dort", dann ist diese Antwort das Ergebnis —
     * sie gehört an den Browser weitergereicht. Würde unser Client der
     * Weiterleitung selbst folgen, käme beim Browser die Seite am Ende der
     * Kette an, und die Adresszeile stimmte nicht mehr. Der
     * Authorization-Code-Flow lebt aber genau von diesen Sprüngen.
     *
     * <p><b>Zweitens: Fehlerstatus nicht als Ausnahme behandeln.</b> Im
     * Normalfall wirft Spring bei 4xx und 5xx. Für einen Proxy ist das falsch:
     * meldet Keycloak "401 falsches Passwort", ist das eine gültige Antwort,
     * die der Benutzer sehen soll — und kein Fehler unseres Gateways.
     *
     * <p>Der eigene Status-Prüfer muss dafür auf Fehler <em>zutreffen</em> und
     * dann nichts tun. Ein Prüfer, der nie zutrifft, hilft nicht: Spring
     * benutzt seine eigene Behandlung immer dann, wenn kein eingetragener
     * Prüfer angesprungen ist — und würde weiterhin werfen.
     */
    @Bean
    public RestClient keycloakRestClient() {
        HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);

        return RestClient.builder()
                .requestFactory(requestFactory)
                .defaultStatusHandler(HttpStatusCode::isError, (anfrage, antwort) -> {
                    // Absichtlich leer: der Status wird weitergereicht,
                    // nicht behandelt.
                })
                .build();
    }
}
