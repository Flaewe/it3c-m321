package ch.benedict.m321.webgateway;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des web-gateway.
 *
 * Dieser Dienst ist der einzige im ganzen System, der einen Port nach aussen
 * veröffentlicht. Er hat deshalb zwei Aufgaben, die sonst nichts miteinander
 * zu tun haben: er prüft jedes ankommende Token, und er reicht den Login-Dienst
 * Keycloak durch, damit der Browser ihn überhaupt erreichen kann.
 */
@SpringBootApplication
public class WebGatewayApplication {

    public static void main(String[] args) {
        SpringApplication.run(WebGatewayApplication.class, args);
    }
}
