package us.nusun.tack.framework.tests.component;

import org.junit.jupiter.api.Test;
import us.nusun.tack.example.routine.ArchitectRoutine;
import us.nusun.tack.framework.projection.ProjectionStore;
import us.nusun.tack.framework.tack.TackComponent;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.TestCamelContexts;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArchitectIntegrationLoopTest {
    @Test
    void previousRecoveryDoesNotContinueAQuietIteration() throws Exception {
        var batches = new AtomicInteger();
        var recoveries = new AtomicInteger();

        try (var context = TestCamelContexts.quiet()) {
            context.addComponent("tack", new TackComponent(FrameworkFixtures.primitiveExecutor(new ProjectionStore())));
            context.addRoutes(new ArchitectRoutine() {
                @Override
                public void configure() {
                    interceptSendToEndpoint("tack:act?id=architect-integration-batch*")
                            .skipSendToOriginalEndpoint().process(exchange -> batches.incrementAndGet());
                    interceptSendToEndpoint("tack:decide?id=architect-needs-conflict-recovery*")
                            .skipSendToOriginalEndpoint().process(exchange -> exchange.setProperty(
                                    "decision.architect-needs-conflict-recovery.result",
                                    batches.get() == 1 ? "yes" : "no"));
                    interceptSendToEndpoint("tack:act?id=architect-conflict-recovery*")
                            .skipSendToOriginalEndpoint().process(exchange -> {
                                recoveries.incrementAndGet();
                                exchange.setProperty("act.architect-conflict-recovery.durableProgress", true);
                            });
                    super.configure();
                }
            });
            context.start();
            try (var producer = context.createProducerTemplate()) {
                var exchange = producer.request("direct:architect-integration-focus", message -> {});
                if (exchange.getException() != null) {
                    throw exchange.getException();
                }
                assertEquals(1, recoveries.get(), exchange.getProperties().toString());
                assertEquals(2, batches.get(), exchange.getProperties().toString());
            }
        }
    }
}
