package ch.benedict.m321.batchwriter.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Was dieser Dienst beim Start am Broker anmeldet.
 *
 * Die Queues werden hier noch einmal deklariert, obwohl der chat-service
 * dasselbe tut. Das Anlegen ist wiederholbar — existiert die Queue schon,
 * passiert nichts. Der Gewinn: der batch-writer kann auch dann starten,
 * wenn der chat-service noch nie gelaufen ist.
 */
@Configuration
@EnableConfigurationProperties(BatchWriterProperties.class)
public class RabbitConfig {

    /**
     * Der Name der Stapel-Fabrik. Der Listener nennt ihn ausdrücklich in
     * seiner Annotation — siehe MessageBatchListener.
     */
    public static final String BATCH_CONTAINER_FACTORY = "batchListenerContainerFactory";

    /**
     * Der Schreibweg. Die Einstellungen müssen Zeichen für Zeichen zu
     * denen im chat-service passen: RabbitMQ lehnt eine Queue ab, die
     * bereits mit anderen Eigenschaften existiert.
     */
    @Bean
    public Queue persistQueue() {
        return QueueBuilder.durable(QueueNames.PERSIST_QUEUE)
                .deadLetterExchange("")
                .deadLetterRoutingKey(QueueNames.DEAD_LETTER_QUEUE)
                .build();
    }

    /** Das Abstellgleis für dauerhaft kaputte Nachrichten. */
    @Bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(QueueNames.DEAD_LETTER_QUEUE).build();
    }

    /**
     * Die Fabrik für den Verbraucher — hier steckt der ganze Stapelbetrieb.
     *
     * <p><b>setConsumerBatchEnabled</b> lässt den Verbraucher selbst
     * sammeln, statt auf Stapel zu warten, die der Erzeuger gebündelt hat.
     * Das ist genau richtig: der chat-service schickt einzeln, gebündelt
     * wird erst hier.
     *
     * <p><b>setBatchSize</b> und <b>setReceiveTimeout</b> sind die beiden
     * Grenzen aus PLANUNG.md: 500 Nachrichten oder 200 Millisekunden, was
     * zuerst eintritt.
     *
     * <p><b>setPrefetchCount</b> muss mindestens so gross sein wie die
     * Stapelgrösse. Sonst gibt RabbitMQ nie genug Nachrichten heraus, um
     * einen vollen Stapel zu füllen, und jeder Stapel liefe in die
     * Zeitgrenze.
     *
     * <p>Die Bestätigung bleibt bei AUTO: Spring bestätigt den ganzen
     * Stapel, nachdem die Methode ohne Ausnahme zurückgekehrt ist — also
     * nach dem COMMIT. Wirft sie, wird nichts bestätigt.
     */
    @Bean(name = BATCH_CONTAINER_FACTORY)
    public SimpleRabbitListenerContainerFactory batchListenerContainerFactory(
            ConnectionFactory connectionFactory,
            BatchWriterProperties properties) {

        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setBatchListener(true);
        factory.setConsumerBatchEnabled(true);
        factory.setBatchSize(properties.batchSize());
        factory.setReceiveTimeout(properties.batchTimeoutMs());
        factory.setPrefetchCount(properties.batchSize());

        // Lehnt der Listener eine Nachricht ab, geht sie zurueck in die
        // Queue. Nur wer ausdruecklich AmqpRejectAndDontRequeueException
        // wirft, schickt sie in die Dead-Letter-Queue. Diese Unterscheidung
        // ist der Kern der Fehlerbehandlung, siehe MessageBatchListener.
        factory.setDefaultRequeueRejected(true);

        return factory;
    }
}
