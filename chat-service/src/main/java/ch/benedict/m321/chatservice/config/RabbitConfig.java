package ch.benedict.m321.chatservice.config;

import ch.benedict.m321.chatservice.delivery.DeliveryListener;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

/**
 * Legt beim Start alles an, was der Broker braucht.
 *
 * Spring meldet diese Beans beim Verbindungsaufbau an RabbitMQ. Existiert
 * eine Queue schon, passiert nichts — das Anlegen ist wiederholbar.
 */
@Configuration
public class RabbitConfig {

    /**
     * So viele Nachrichten hält die eigene Queue einer Instanz höchstens
     * (Spezifikation 2 und 3.5). Hängt die Instanz, wächst die Queue nicht
     * ins Unendliche.
     */
    private static final int DELIVERY_QUEUE_MAX_LENGTH = 1000;

    /**
     * Der Schreibweg. "durable" heisst: die Queue überlebt einen Neustart
     * des Brokers. Was der Verbraucher endgültig ablehnt, landet über den
     * Standard-Exchange ("") in der Dead-Letter-Queue.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für Nachrichten, die niemand verarbeiten konnte. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Der Zustellweg. Fanout heisst: JEDE gebundene Queue bekommt eine
     * Kopie. Das ist genau richtig, weil jede chat-service-Instanz jede
     * Nachricht sehen muss — nur sie weiss, welche ihrer SSE-Clients im
     * betroffenen Raum sitzen.
     */
    @Bean
    public FanoutExchange deliveryExchange() {
        return new FanoutExchange(QueueNames.DELIVERY_EXCHANGE, true, false);
    }

    /**
     * Die eigene Queue dieser Instanz am Zustellweg (Spezifikation 2).
     *
     * - Leerer Name: Den Namen vergibt der Broker (amq.gen-...). Jede
     *   Instanz bekommt so automatisch eine andere Queue. Ein fester Name
     *   hiesse: alle Instanzen teilen sich eine Queue, und RabbitMQ würde
     *   die Nachrichten zwischen ihnen aufteilen statt jeder eine Kopie zu
     *   geben. AnonymousQueue nehmen wir nicht, weil dort Spring den Namen
     *   erfindet (spring.gen-...), nicht der Broker.
     * - Nicht durable, exclusive, auto-delete: Die Queue gehört nur dieser
     *   Verbindung. Endet sie, löscht RabbitMQ die Queue (Spezifikation 3.3).
     * - x-max-length 1000 mit drop-head: Hängt die Instanz, fliegen die
     *   ältesten Nachrichten raus, statt dass der Broker vollläuft (3.5).
     */
    @Bean
    public Queue deliveryQueue() {
        Map<String, Object> arguments = new HashMap<>();
        arguments.put("x-max-length", DELIVERY_QUEUE_MAX_LENGTH);
        arguments.put("x-overflow", "drop-head");

        boolean durable = false;
        boolean exclusive = true;
        boolean autoDelete = true;
        return new Queue("", durable, exclusive, autoDelete, arguments);
    }

    /**
     * Hängt die eigene Queue an den Fanout-Exchange. Erst dadurch bekommt
     * sie eine Kopie jeder Nachricht.
     *
     * Weil die Queue noch keinen Namen hat, merkt sich das Binding die
     * Queue selbst und fragt den Namen erst ab, wenn es beim Broker angelegt
     * wird. Das ist nach jeder Neuverbindung ein neuer Name.
     */
    @Bean
    public Binding deliveryBinding(@Qualifier("deliveryQueue") Queue deliveryQueue,
                                   FanoutExchange deliveryExchange) {
        return BindingBuilder.bind(deliveryQueue).to(deliveryExchange);
    }

    /**
     * Holt die Nachrichten aus der eigenen Queue und gibt sie an den
     * DeliveryListener.
     *
     * - setQueues mit dem Queue-Objekt, nicht mit einem Namen: So fragt der
     *   Container bei jedem Neustart des Consumers den aktuellen Namen ab.
     *   Nach einer Neuverbindung hat die Queue einen neuen Namen (3.5).
     * - Kein Message-Converter: Der Listener bekommt die rohen Bytes. Nur so
     *   bleibt das JSON unverändert (Spezifikation 1.1).
     */
    @Bean
    public SimpleMessageListenerContainer deliveryListenerContainer(
            ConnectionFactory connectionFactory,
            @Qualifier("deliveryQueue") Queue deliveryQueue,
            DeliveryListener deliveryListener) {
        SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(connectionFactory);
        container.setQueues(deliveryQueue);
        container.setMessageListener(deliveryListener);

        // Nach einer Neuverbindung versucht der Container zuerst noch den
        // alten Namen, bevor die neue Queue angelegt ist. So beschreibt es
        // die Spring-AMQP-Dokumentation unter "broker-named queues". Ohne
        // diese Einstellung kann der Consumer dann aufhören, statt es weiter
        // zu versuchen. Das passiert nur manchmal, darum steht es von Anfang
        // an hier.
        container.setMissingQueuesFatal(false);

        // Wirft die Verarbeitung irgendeine Ausnahme, wird die Nachricht
        // verworfen und nicht zurück in die Queue gestellt. Sonst käme sie
        // ein zweites Mal, stünde doppelt in der Liste des Raums, und die
        // Clients bekämen sie doppelt.
        container.setDefaultRequeueRejected(false);
        return container;
    }

    /**
     * Nachrichten gehen als JSON über die Leitung, nicht als serialisiertes
     * Java-Objekt. Nur so kann später ein Dienst in einer anderen Sprache
     * mitlesen.
     *
     * Der ObjectMapper kommt von Spring Boot und kann bereits Instant im
     * ISO-8601-Format schreiben.
     */
    @Bean
    public MessageConverter jsonMessageConverter(ObjectMapper objectMapper) {
        return new Jackson2JsonMessageConverter(objectMapper);
    }
}
