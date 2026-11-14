package ch.benedict.m321.chatservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Startpunkt des chat-service.
 *
 * Dieser Dienst ist die einzige Stelle im System, die eine Nachricht vom
 * Benutzer entgegennimmt. Er speichert nichts selbst — er reicht die
 * Nachricht an RabbitMQ weiter.
 */
@SpringBootApplication
public class ChatServiceApplication {

    /** Startet den Dienst. Spring Boot baut daraus den Web-Server, die Verbindung zu RabbitMQ und alle Beans. */
    public static void main(String[] args) {
        SpringApplication.run(ChatServiceApplication.class, args);
    }
}
