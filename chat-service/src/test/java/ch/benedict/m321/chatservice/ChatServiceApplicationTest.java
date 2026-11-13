package ch.benedict.m321.chatservice;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Prüft, dass der Spring-Kontext überhaupt hochfährt.
 *
 * Ein RabbitMQ wird dafür nicht gebraucht. Der Listener-Container des
 * Zustellwegs versucht zwar schon beim Start, sich zu verbinden. Findet er
 * keinen Broker, meldet er "Broker not available" und versucht es im
 * Hintergrund alle paar Sekunden wieder. Der Start wartet nicht darauf.
 * Genau so soll es auch im Betrieb sein: Ist RabbitMQ kurz weg, startet
 * der Dienst trotzdem und verbindet sich, sobald der Broker da ist.
 */
@SpringBootTest
class ChatServiceApplicationTest {

    @Test
    void contextLoads() {
        // Kein Assert nötig. Fährt der Kontext nicht hoch, wirft Spring
        // eine Exception und der Test wird rot.
    }
}
