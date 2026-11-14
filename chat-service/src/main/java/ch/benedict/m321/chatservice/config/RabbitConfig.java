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
     * So viele Nachrichten dürfen in der eigenen Queue einer Instanz
     * WARTEN (Spezifikation 2 und 3.5). Hängt die Instanz, wächst die Queue
     * nicht ins Unendliche.
     *
     * 999 statt 1000: Dazu kommt höchstens 1 Nachricht, die schon an die
     * Instanz ausgeliefert, aber noch nicht bestätigt ist (prefetch 1, siehe
     * deliveryListenerContainer). x-max-length zählt die nicht mit. Zusammen
     * sind es so nie mehr als 1000, gemessen in Plan-Schritt 9.
     */
    private static final int DELIVERY_QUEUE_MAX_LENGTH = 999;

    /**
     * So viele Nachrichten holt der Container höchstens auf Vorrat ab, bevor
     * er die erste bestätigt hat. Der Standard von Spring ist 250.
     */
    private static final int DELIVERY_PREFETCH_COUNT = 1;

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
     * - x-max-length 999 mit drop-head: Hängt die Instanz, fliegen die
     *   ältesten Nachrichten raus, statt dass der Broker vollläuft (3.5).
     *   Mit der einen unbestätigten sind es höchstens 1000.
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

        // Nach einer Neuverbindung startet der Consumer neu und fragt den
        // Namen der Queue ab, ein paar Millisekunden BEVOR die neue Queue
        // angelegt ist. Er bekommt also noch den alten Namen, und den gibt
        // es nicht mehr. So beschreibt es auch die Spring-AMQP-Dokumentation
        // unter "broker-named queues". Gemessen in Schritt 4 des Plans:
        //
        // - setMissingQueuesFatal(false): Eine fehlende Queue hält den
        //   Container nicht für immer an. Mit true meldet er "Stopping
        //   container from aborted consumer" und holt nie wieder ab.
        // - setDeclarationRetries(0): Nicht noch dreimal im Abstand von
        //   5 Sekunden den alten Namen versuchen, sondern den Consumer gleich
        //   neu starten. Dabei fragt er den neuen Namen ab. Die Instanz stellt
        //   so nach etwa 5 statt 20 Sekunden wieder zu.
        container.setMissingQueuesFatal(false);
        container.setDeclarationRetries(0);

        // Wirft die Verarbeitung irgendeine Ausnahme, wird die Nachricht
        // verworfen und nicht zurück in die Queue gestellt. Sonst käme sie
        // ein zweites Mal, stünde doppelt in der Liste des Raums, und die
        // Clients bekämen sie doppelt.
        container.setDefaultRequeueRejected(false);

        // Nur 1 Nachricht auf Vorrat (Plan, Schritt 9). Mit dem Standard 250
        // zeigte die Queue einer hängenden Instanz 1000 wartende plus 250
        // ausgelieferte, aber unbestätigte, also 1250 statt höchstens 1000
        // (S6). x-max-length zählt die unbestätigten nicht mit.
        //
        // Warum nicht ganz ohne Bestätigung (AcknowledgeMode.NONE): Dann
        // nimmt eine Instanz, die noch läuft, aber nicht nachkommt, alle
        // Nachrichten an und stapelt sie ohne Grenze im eigenen
        // Arbeitsspeicher. Gemessen in Schritt 9: 0 in der Queue, alle 2000
        // in der Instanz. Das Problem wäre nur vom Broker in die Instanz
        // gewandert.
        container.setPrefetchCount(DELIVERY_PREFETCH_COUNT);
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
