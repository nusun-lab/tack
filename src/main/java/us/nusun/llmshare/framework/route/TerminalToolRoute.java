package us.nusun.llmshare.framework.route;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;

import java.util.LinkedHashMap;

@ApplicationScoped
public class TerminalToolRoute extends RouteBuilder {
    public static final String TAG = "tack-terminal";

    @Inject ObjectMapper objectMapper;

    @Override
    public void configure() {
        from("langchain4j-tools:finish_act"
                + "?tags=" + TAG
                + "&name=finish_act"
                + "&description=Complete%20the%20current%20act%20with%20its%20result%20and%20durable%20thought"
                + "&parameter.summary=string"
                + "&parameter.summary.required=true"
                + "&parameter.summary.description=Compact%20externally%20useful%20result%20of%20the%20act"
                + "&parameter.content=string"
                + "&parameter.content.required=true"
                + "&parameter.content.description=Complete%20replacement%20content%20for%20the%20current%20durable%20thought")
                .routeId("finish-act-tool")
                .process(exchange -> {
                    String summary = exchange.getMessage().getHeader("summary", String.class);
                    String content = exchange.getMessage().getHeader("content", String.class);
                    if (summary == null || summary.isBlank()) {
                        throw new IllegalArgumentException("finish_act requires summary");
                    }
                    if (content == null || content.isBlank()) {
                        throw new IllegalArgumentException("finish_act requires content");
                    }
                    LinkedHashMap<String, String> result = new LinkedHashMap<>();
                    result.put("summary", summary.strip());
                    result.put("content", content.strip());
                    exchange.getMessage().setBody(objectMapper.writeValueAsString(result));
                });
    }
}
