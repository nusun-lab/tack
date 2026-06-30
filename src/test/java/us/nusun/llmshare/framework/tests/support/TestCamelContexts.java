package us.nusun.llmshare.framework.tests.support;

import org.apache.camel.StartupSummaryLevel;
import org.apache.camel.impl.DefaultCamelContext;
import org.jboss.logmanager.Level;
import org.jboss.logmanager.LogContext;

public final class TestCamelContexts {
    static {
        LogContext.getLogContext()
                .getLogger("org.apache.camel.impl.engine.AbstractCamelContext")
                .setLevel(Level.WARN);
        LogContext.getLogContext()
                .getLogger("org.apache.camel.impl.engine.DefaultShutdownStrategy")
                .setLevel(Level.ERROR);
    }

    private TestCamelContexts() {
    }

    public static DefaultCamelContext quiet() {
        DefaultCamelContext context = new DefaultCamelContext();
        context.setStartupSummaryLevel(StartupSummaryLevel.Off);
        context.getShutdownStrategy().setSuppressLoggingOnTimeout(true);
        return context;
    }
}
