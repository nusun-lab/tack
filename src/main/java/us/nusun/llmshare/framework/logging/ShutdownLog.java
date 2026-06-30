package us.nusun.llmshare.framework.logging;

import io.quarkus.runtime.ShutdownEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ShutdownLog {
    private static final Logger LOG = Logger.getLogger(ShutdownLog.class);

    void onShutdown(@Observes ShutdownEvent ignored) {
        LOG.info("Shutdown in progress. Kafka consumers may be interrupted for fast exit.");
    }
}
