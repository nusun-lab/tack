package us.nusun.tack.framework.tack;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import org.apache.camel.Endpoint;
import org.apache.camel.support.DefaultComponent;

import java.util.Map;

@ApplicationScoped
@Named("tack")
public class TackComponent extends DefaultComponent {
    @Inject PrimitiveExecutor executor;

    public TackComponent() {
    }

    public TackComponent(PrimitiveExecutor executor) {
        this.executor = executor;
    }

    @Override
    protected Endpoint createEndpoint(String uri, String remaining, Map<String, Object> parameters) throws Exception {
        TackEndpoint endpoint = new TackEndpoint(uri, this, executor);
        endpoint.setOperation(remaining);
        setProperties(endpoint, parameters);
        return endpoint;
    }
}
