package us.nusun.llmshare.framework.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.Exchange;
import org.apache.camel.Expression;
import org.apache.camel.Processor;

import java.util.ArrayList;
import java.util.List;

import static org.apache.camel.support.builder.ExpressionBuilder.constantExpression;

@ApplicationScoped
public class RoutineSupport {
    public static final String THOUGHT_SCOPE_NAMES_PROPERTY = "routineThoughtScopeNames";

    @Inject ThoughtStackSupport thoughts;

    public Act act(String id) {
        return new Act(id);
    }

    public Decision decide(String id) {
        return new Decision(id);
    }

    public Creation create(String id) {
        return new Creation(id);
    }

    public Processor push(String context, int maxTokens) {
        Thought thought = new Thought(context, maxTokens);
        return thoughts.push(thought.context, thought.maxTokens);
    }

    public Processor pop() {
        return thoughts.pop();
    }

    public Processor enterThought(String name, String context, int maxTokens) {
        ThoughtName thoughtName = new ThoughtName(name);
        Thought thought = new Thought(context, maxTokens);
        return exchange -> {
            thoughts.push(thought.context, thought.maxTokens).process(exchange);
            thoughtScopeNames(exchange).add(thoughtName.value);
        };
    }

    public Processor exitThought(String name) {
        ThoughtName thoughtName = new ThoughtName(name);
        return exchange -> {
            List<String> names = thoughtScopeNames(exchange);
            if (names.isEmpty()) {
                throw new IllegalStateException(
                        "Cannot exit thought scope " + thoughtName.value
                                + " because no thought scope is active");
            }
            String active = names.getLast();
            if (!active.equals(thoughtName.value)) {
                throw new IllegalStateException(
                        "Cannot exit thought scope " + thoughtName.value
                                + " because active thought scope is " + active);
            }
            thoughts.pop().process(exchange);
            names.removeLast();
        };
    }

    private static void setProperty(Exchange exchange, String name, String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
        exchange.setProperty(name, value);
    }

    private static void setPromptProperty(Exchange exchange, String name, Expression prompt) {
        if (prompt != null) {
            prompt.init(exchange.getContext());
        }
        Object value = prompt == null ? null : prompt.evaluate(exchange, Object.class);
        String text = value == null ? null : String.valueOf(value).strip();
        setProperty(exchange, name, text);
    }

    private static void setOptionalProperty(Exchange exchange, String name, Object value) {
        if (value != null) {
            exchange.setProperty(name, value);
        }
    }

    @SuppressWarnings("unchecked")
    private static List<String> thoughtScopeNames(Exchange exchange) {
        List<String> names = exchange.getProperty(
                THOUGHT_SCOPE_NAMES_PROPERTY,
                List.class);
        if (names == null) {
            names = new ArrayList<>();
            exchange.setProperty(THOUGHT_SCOPE_NAMES_PROPERTY, names);
        }
        return names;
    }

    public static final class Act implements Processor {
        private final String id;
        private Expression prompt;
        private String toolProfile = "none";
        private String toolRuntime;
        private String synthesisRuntime;
        private Integer thoughtUpdateDepth;

        private Act(String id) {
            this.id = id;
        }

        public Act prompt(String prompt) {
            this.prompt = prompt == null ? null : constantExpression(prompt.strip());
            return this;
        }

        public Act prompt(Expression prompt) {
            this.prompt = prompt;
            return this;
        }

        public Act toolProfile(String toolProfile) {
            this.toolProfile = toolProfile;
            return this;
        }

        public Act toolRuntime(String toolRuntime) {
            this.toolRuntime = toolRuntime;
            return this;
        }

        public Act synthesisRuntime(String synthesisRuntime) {
            this.synthesisRuntime = synthesisRuntime;
            return this;
        }

        public Act thoughtUpdateDepth(int thoughtUpdateDepth) {
            this.thoughtUpdateDepth = thoughtUpdateDepth;
            return this;
        }

        @Override
        public void process(Exchange exchange) {
            setProperty(exchange, "actId", id);
            setPromptProperty(exchange, "actPrompt", prompt);
            setProperty(exchange, "toolProfile", toolProfile);
            setOptionalProperty(exchange, "toolRuntime", toolRuntime);
            setOptionalProperty(exchange, "synthesisRuntime", synthesisRuntime);
            setOptionalProperty(exchange, "thoughtUpdateDepth", thoughtUpdateDepth);
        }
    }

    public static final class Decision implements Processor {
        private final String id;
        private Expression prompt;
        private String synthesisRuntime;

        private Decision(String id) {
            this.id = id;
        }

        public Decision prompt(String prompt) {
            this.prompt = prompt == null ? null : constantExpression(prompt.strip());
            return this;
        }

        public Decision prompt(Expression prompt) {
            this.prompt = prompt;
            return this;
        }

        public Decision synthesisRuntime(String synthesisRuntime) {
            this.synthesisRuntime = synthesisRuntime;
            return this;
        }

        @Override
        public void process(Exchange exchange) {
            setProperty(exchange, "decisionId", id);
            setPromptProperty(exchange, "decisionPrompt", prompt);
            setOptionalProperty(exchange, "synthesisRuntime", synthesisRuntime);
        }
    }

    public static final class Creation implements Processor {
        private final String id;
        private Expression prompt;
        private String schemaId;
        private String schema;
        private String schemaJson;
        private String schemaUri;
        private String synthesisRuntime;

        private Creation(String id) {
            this.id = id;
        }

        public Creation prompt(String prompt) {
            this.prompt = prompt == null ? null : constantExpression(prompt.strip());
            return this;
        }

        public Creation prompt(Expression prompt) {
            this.prompt = prompt;
            return this;
        }

        public Creation schemaId(String schemaId) {
            this.schemaId = schemaId;
            return this;
        }

        public Creation schema(String schema) {
            this.schema = schema;
            return this;
        }

        public Creation schemaJson(String schemaJson) {
            this.schemaJson = schemaJson;
            return this;
        }

        public Creation schemaUri(String schemaUri) {
            this.schemaUri = schemaUri;
            return this;
        }

        public Creation synthesisRuntime(String synthesisRuntime) {
            this.synthesisRuntime = synthesisRuntime;
            return this;
        }

        @Override
        public void process(Exchange exchange) {
            setProperty(exchange, "createId", id);
            setPromptProperty(exchange, "createPrompt", prompt);
            setOptionalProperty(exchange, "schemaId", schemaId);
            setOptionalProperty(exchange, "createSchema", schema);
            setOptionalProperty(exchange, "createSchemaJson", schemaJson);
            setOptionalProperty(exchange, "createSchemaUri", schemaUri);
            setOptionalProperty(exchange, "synthesisRuntime", synthesisRuntime);
        }
    }

    private record Thought(String context, int maxTokens) {
        public Thought {
            if (context == null || context.isBlank()) {
                throw new IllegalArgumentException("thought context is required");
            }
            context = context.strip();
            if (maxTokens <= 0) {
                throw new IllegalArgumentException("thought maxTokens must be positive");
            }
        }
    }

    private record ThoughtName(String value) {
        private ThoughtName(String value) {
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("thought scope name is required");
            }
            this.value = value.strip();
        }
    }
}
