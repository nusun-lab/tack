package us.nusun.tack.framework.routine;

import org.apache.camel.Category;
import org.apache.camel.Consumer;
import org.apache.camel.Processor;
import org.apache.camel.Producer;
import org.apache.camel.spi.UriEndpoint;
import org.apache.camel.spi.UriParam;
import org.apache.camel.spi.UriPath;
import org.apache.camel.support.DefaultEndpoint;

@UriEndpoint(firstVersion = "0.1.0", scheme = "tack", title = "Tack", syntax = "tack:operation", producerOnly = true, category = {Category.CORE})
public class TackEndpoint extends DefaultEndpoint {
    private final PrimitiveExecutor executor;

    @UriPath(description = "Primitive operation to execute")
    private String operation;

    @UriParam(description = "Operation identifier")
    private String id;

    @UriParam(description = "Tool profile id for act operations")
    private String toolProfile;

    @UriParam(description = "Thought name for thought-enter and thought-exit")
    private String name;

    @UriParam(description = "Maximum approximate token budget for a thought")
    private Integer maxTokens;

    @UriParam(description = "Inference lane override")
    private String lane;

    @UriParam(description = "Comma-separated allowed decision choices; defaults to yes,no")
    private String choices;

    @UriParam(description = "Create result schema id")
    private String schemaId;

    @UriParam(description = "Create result schema JSON or URI")
    private String schema;

    public TackEndpoint(String endpointUri, TackComponent component, PrimitiveExecutor executor) {
        super(endpointUri, component);
        this.executor = executor;
    }

    @Override
    public Producer createProducer() {
        return new TackProducer(this, executor);
    }

    @Override
    public Consumer createConsumer(Processor processor) {
        throw new UnsupportedOperationException("tack endpoints are producer-only");
    }

    @Override
    public boolean isSingleton() {
        return true;
    }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getToolProfile() { return toolProfile; }
    public void setToolProfile(String toolProfile) { this.toolProfile = toolProfile; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }
    public String getLane() { return lane; }
    public void setLane(String lane) { this.lane = lane; }
    public String getChoices() { return choices; }
    public void setChoices(String choices) { this.choices = choices; }
    public String getSchemaId() { return schemaId; }
    public void setSchemaId(String schemaId) { this.schemaId = schemaId; }
    public String getSchema() { return schema; }
    public void setSchema(String schema) { this.schema = schema; }
}
