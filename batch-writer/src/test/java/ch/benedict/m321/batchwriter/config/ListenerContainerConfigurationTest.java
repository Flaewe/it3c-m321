package ch.benedict.m321.batchwriter.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.benedict.m321.batchwriter.repository.MessageRepository;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Prüft, dass der Listener wirklich im Stapelbetrieb läuft.
 *
 * <p>Dieser Test ist aus einem echten Fehler entstanden. Die erste Fassung
 * hat die Stapel-Fabrik über einen RabbitListenerConfigurer als Vorgabe
 * eintragen wollen. Das hat nicht gegriffen: der Container lief mit der
 * Standardfabrik von Spring Boot, also mit batchSize 1 und ohne
 * Stapelbetrieb. Jede Nachricht landete in der Dead-Letter-Queue, die
 * Tabelle blieb leer — und kein Test ohne echten Broker hat es bemerkt.
 *
 * <p>Dieser Test braucht weder Broker noch Datenbank. Er startet den
 * Anwendungskontext, ohne Datenbank, und fragt den fertig gebauten
 * Container, wie er eingestellt ist. Der Broker steht auf einem Port, auf
 * dem niemand lauscht — verbinden muss sich hier niemand.
 */
@SpringBootTest(properties = {
        "spring.autoconfigure.exclude="
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.JdbcTemplateAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "spring.rabbitmq.port=1"})
class ListenerContainerConfigurationTest {

    /**
     * Ersetzt das Repository, weil es in diesem Test keine Datenbank gibt.
     * Geschrieben wird hier ohnehin nichts.
     */
    @MockitoBean
    private MessageRepository messageRepository;

    @Autowired
    private RabbitListenerEndpointRegistry registry;

    /**
     * Der eine Listener des Dienstes muss mit der Stapel-Fabrik gebaut sein.
     *
     * consumerBatchEnabled setzt nur unsere Fabrik. Steht hier false, läuft
     * der Container mit der Standardfabrik von Spring Boot — und das ist
     * genau der Fehler, den dieser Test verhindern soll.
     */
    @Test
    void listenerRunsInBatchMode() {
        Collection<MessageListenerContainer> container = registry.getListenerContainers();
        assertThat(container).hasSize(1);

        MessageListenerContainer einziger = container.iterator().next();
        assertThat(einziger).isInstanceOf(SimpleMessageListenerContainer.class);

        SimpleMessageListenerContainer stapelContainer = (SimpleMessageListenerContainer) einziger;
        assertThat(stapelContainer.isConsumerBatchEnabled()).isTrue();
    }

    /**
     * Bestätigt wird automatisch nach dem Methodenaufruf — also erst nach
     * dem COMMIT. Darauf beruht At-least-once aus Spezifikation 3.1.
     */
    @Test
    void acknowledgesAfterTheMethodReturns() {
        MessageListenerContainer einziger = registry.getListenerContainers().iterator().next();
        SimpleMessageListenerContainer stapelContainer = (SimpleMessageListenerContainer) einziger;

        assertThat(stapelContainer.getAcknowledgeMode()).isEqualTo(AcknowledgeMode.AUTO);
    }
}
