package us.nusun.tack.framework.tests.support;

import org.apache.camel.Consumer;
import org.apache.camel.Endpoint;
import org.apache.camel.Exchange;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.component.kafka.KafkaConstants;
import org.apache.camel.support.DefaultComponent;
import org.apache.camel.support.DefaultConsumer;
import org.apache.camel.support.DefaultEndpoint;
import org.apache.camel.support.DefaultProducer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class StubKafkaComponent extends DefaultComponent {
    private final SendObserver observer;
    private final List<SentExchange> sent = new ArrayList<>();
    private final List<ConsumerRegistration> consumers = new ArrayList<>();

    public StubKafkaComponent() {
        this((topic, exchange) -> { });
    }

    public StubKafkaComponent(SendObserver observer) {
        this.observer = observer;
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        String resolvedRemaining = getCamelContext().resolvePropertyPlaceholders(remaining);
        return new StubKafkaEndpoint(uri, this, resolvedRemaining);
    }

    public List<String> sentTopics() {
        return sent.stream().map(SentExchange::topic).toList();
    }

    public List<Exchange> sentTo(String topic) {
        return sent.stream()
                .filter(item -> item.topic().equals(topic))
                .map(SentExchange::exchange)
                .toList();
    }

    public void emit(String topic, Object body) throws Exception {
        List<ConsumerRegistration> matches = consumers.stream()
                .filter(consumer -> consumer.topic().equals(topic))
                .toList();
        if (matches.isEmpty()) {
            throw new IllegalStateException("No stub Kafka consumer registered for topic " + topic);
        }
        for (ConsumerRegistration consumer : matches) {
            Exchange exchange = consumer.endpoint().createExchange();
            exchange.getIn().setBody(body);
            consumer.processor().process(exchange);
        }
    }

    public interface SendObserver {
        void sent(String topic, Exchange exchange) throws Exception;
    }

    private static final class StubKafkaEndpoint extends DefaultEndpoint {
        private final StubKafkaComponent component;
        private final String topic;

        private StubKafkaEndpoint(String endpointUri, StubKafkaComponent component, String topic) {
            super(endpointUri, component);
            this.component = component;
            this.topic = normalizeTopic(topic);
        }

        @Override
        public Producer createProducer() {
            return new DefaultProducer(this) {
                @Override
                public void process(Exchange exchange) throws Exception {
                    Object overrideTopic = exchange.getMessage().removeHeader(KafkaConstants.OVERRIDE_TOPIC);
                    String destination = overrideTopic == null ? topic : overrideTopic.toString();
                    component.sent.add(new SentExchange(destination, exchange.copy()));
                    component.observer.sent(destination, exchange);
                }
            };
        }

        @Override
        public Consumer createConsumer(Processor processor) {
            component.consumers.add(new ConsumerRegistration(topic, this, processor));
            return new DefaultConsumer(this, processor);
        }

        @Override
        public boolean isSingleton() {
            return true;
        }

        @Override
        public boolean isLenientProperties() {
            return true;
        }

        private static String normalizeTopic(String topic) {
            String normalized = topic == null ? "" : topic;
            while (normalized.startsWith("/")) {
                normalized = normalized.substring(1);
            }
            int query = normalized.indexOf('?');
            return query < 0 ? normalized : normalized.substring(0, query);
        }
    }

    private record SentExchange(String topic, Exchange exchange) {
    }

    private record ConsumerRegistration(String topic, StubKafkaEndpoint endpoint, Processor processor) {
    }
}
