package us.nusun.llmshare;

import dev.langchain4j.model.openai.OpenAiChatModel;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.apache.camel.builder.RouteBuilder;
import org.apache.camel.component.langchain4j.agent.api.AgentConfiguration;

@ApplicationScoped
public class SchemaSanitySupport extends RouteBuilder {
    @Produces
    @Singleton
    @Named("schemaNoToolsAgentConfig")
    AgentConfiguration schemaNoToolsAgentConfig(OpenAiChatModel chatModel) {
        return new AgentConfiguration().withChatModel(chatModel);
    }

    @Override
    public void configure() {
        from("direct:schema-sanity-no-tools")
                .routeId("schema-sanity-no-tools")
                .to("langchain4j-agent:software-team-director-llm"
                        + "?agentConfiguration=#schemaNoToolsAgentConfig"
                        + "&jsonSchema=classpath:schemas/schema-sanity-schema.json");

        from("direct:schema-sanity-with-tools")
                .routeId("schema-sanity-with-tools")
                .to("langchain4j-agent:software-team-director-llm"
                        + "?agentConfiguration=#directorAgentConfig"
                        + "&jsonSchema=classpath:schemas/schema-sanity-schema.json");
    }
}
