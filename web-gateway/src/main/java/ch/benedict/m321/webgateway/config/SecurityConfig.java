package ch.benedict.m321.webgateway.config;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Wer darf was, und wie wird ein Token geprüft.
 *
 * Das Gateway ist der einzige Wachposten im System (PLANUNG.md, Abschnitt 3.1).
 * Alles, was hier durchkommt, gilt für die inneren Dienste als geprüft.
 */
@Configuration
@EnableConfigurationProperties(KeycloakProperties.class)
@RequiredArgsConstructor
public class SecurityConfig {

    private final KeycloakProperties keycloakProperties;

    /**
     * Die Regeln, welcher Pfad ein Token braucht.
     */
    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.authorizeHttpRequests(regeln -> regeln
                // Der Login selbst darf kein Token verlangen — sonst bräuchte
                // man ein Token, um an ein Token zu kommen.
                .requestMatchers("/auth/**").permitAll()
                // Die Web-App muss geladen werden können, bevor sich jemand
                // anmeldet. Die Anmeldung passiert erst danach im Browser.
                .requestMatchers("/", "/index.html", "/assets/**", "/favicon.ico").permitAll()
                .anyRequest().authenticated());

        http.oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));

        // Wir halten keine Sitzung. Jede Anfrage bringt ihr eigenes Token mit,
        // und das Gateway kann jederzeit neu starten, ohne dass jemand fliegt.
        http.sessionManagement(sitzung -> sitzung.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // CSRF schützt davor, dass eine fremde Seite den Browser dazu bringt,
        // eine Anfrage MIT den gespeicherten Zugangsdaten zu schicken. Wir
        // benutzen aber keine Cookies zur Anmeldung, sondern einen
        // Authorization-Header, den der Browser nie von selbst mitschickt.
        // Damit greift der Angriff nicht — und der Schutz würde nur die
        // weitergereichten Login-Formulare von Keycloak blockieren.
        http.csrf(csrf -> csrf.disable());

        return http.build();
    }

    /**
     * Prüft die Signatur des Tokens — und den Aussteller.
     *
     * Hier stecken zwei Entscheidungen drin, die sich sonst leicht übersehen
     * lassen:
     *
     * 1. Wir bauen den Decoder aus der <em>jwk-set-uri</em> statt aus der
     *    issuer-uri. Die issuer-uri würde Spring dazu bringen, Keycloak schon
     *    beim Start zu befragen — und Keycloak braucht rund eine halbe Minute
     *    zum Hochfahren. Das Gateway würde dann schneller starten als der
     *    Login-Dienst und sofort wieder sterben. So holen wir den Schlüssel
     *    erst bei der ersten Anfrage.
     *
     * 2. Genau deshalb müssen wir die Prüfung des Ausstellers von Hand
     *    nachrüsten. Ohne sie würde das Gateway jedes Token akzeptieren, das
     *    mit dem richtigen Schlüssel signiert ist — auch eines aus einem
     *    fremden Realm.
     */
    @Bean
    public JwtDecoder jwtDecoder() {
        NimbusJwtDecoder decoder = NimbusJwtDecoder
                .withJwkSetUri(keycloakProperties.jwkSetUri())
                .build();

        OAuth2TokenValidator<Jwt> validator = JwtValidators.createDefaultWithIssuer(keycloakProperties.issuerUri());
        decoder.setJwtValidator(validator);

        return decoder;
    }
}
