package us.nusun.tack.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.tack.example.tool.TeamChatToolProfile;
import us.nusun.tack.framework.inventory.RoutineProvider;
import us.nusun.tack.framework.model.LlmPassType;
import us.nusun.tack.framework.model.RoutineDefinition;
import us.nusun.tack.framework.tack.TackHeaders;

import java.util.List;

@ApplicationScoped
public class ForumAgentsChatRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/forum-agents-chat";
    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Forum Agents Chat")
                .description("""
                        Chat-only continuous routine for the hard-coded forum-agents room. Use it to run multiple
                        personas in the same conversation and exercise thought
                        management. This routine is mostly for testing.
                        """)
                .routeUri("direct:example-forum-agents-chat")
                .defaultLane("high-pass")
                .withLaneBinding(LlmPassType.SYNTHESIS, "low-pass")
                .withLaneBinding(LlmPassType.CONSOLIDATION, "low-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-forum-agents-chat")
                .routeId("example-forum-agents-chat")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Durable chat anchors: Mattermost room forum-agents, this persona's conversational stance, recent
                        questions answered, points to revisit, and how the group conversation is evolving.
                        """))
                .to("tack:thought-enter?name=forum-agents-chat-routine&maxTokens=500")
                .loopDoWhile(constant(true))
                    .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                            Current chat turn: recent forum-agents messages, who spoke last, whether this persona should
                            answer, ask one focused question, add a useful observation, or stay quiet for this turn.
                            """))
                    .to("tack:thought-enter?name=forum-agents-chat-turn&maxTokens=250")
                    .setHeader(TackHeaders.PROMPT, constant("""
                            Use Mattermost room forum-agents only.
                            
                            Read only recent messages in forum-agents. Continue the conversation as this persona. If another
                            persona asked this persona a direct question, answer it. Otherwise add at most one concise
                            message that moves the discussion forward.
                            
                            Do not create channels, tasks, project state, or use any room other than forum-agents. If
                            nothing useful should be said, stay quiet.
                            """))
                    .to("tack:act?id=forum-agents-chat&toolProfile=" + TeamChatToolProfile.ID)
                    .to("tack:thought-exit?name=forum-agents-chat-turn")
                    .delay(5000)
                .end()
                .to("tack:thought-exit?name=forum-agents-chat-routine");
    }
}
