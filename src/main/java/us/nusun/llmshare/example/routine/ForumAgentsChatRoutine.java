package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.TeamChatToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.routine.RoutineSupport;

import java.util.List;

@ApplicationScoped
public class ForumAgentsChatRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/forum-agents-chat";
    public static final String ROOM = "animals";

    @Inject RoutineSupport routine;

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(routine());
    }

    public RoutineDefinition routine() {
        return RoutineDefinition.builder(ID)
                .name("Forum Agents Chat")
                .description("""
                        Chat-only continuous routine for the hard-coded forum-agents room.
                        Use it to run multiple personas in the same conversation and exercise thought management.
                        """)
                .routeUri("direct:example-forum-agents-chat")
                .defaultLane("high-pass")
                .build();
    }

    @Override
    public void configure() {
        from("direct:example-forum-agents-chat")
                .routeId("example-forum-agents-chat")
                .process(routine.enterThought("forum-agents-chat-routine", """
                        Durable chat anchors: Mattermost room forum-agents, this persona's conversational stance,
                        recent questions answered, points to revisit, and how the group conversation is evolving.
                        This routine is chat-only. Do not use project-management, source-control, workspace, or
                        orchestration tools.
                        """, 500))
                .setProperty("forumAgentsChatTurn", constant(0))
                .loopDoWhile(constant(true))
                    .setProperty("forumAgentsChatTurn", simple("${exchangeProperty.forumAgentsChatTurn++}"))
                    .process(routine.enterThought("forum-agents-chat-turn", """
                        Current chat turn: recent forum-agents messages, who spoke last, whether this persona should
                        answer, ask one focused question, add a useful observation, or stay quiet for this turn.
                        Promote durable conversation state to forum-agents-chat-routine before the turn exits.
                        """, 250))
                    .process(routine.act("forum-agents-chat")
                            .prompt("""
                                    Use Mattermost room %s only.

                                    Read only recent messages in %s. Continue the conversation as this persona.
                                    If another persona asked this persona a direct question, answer it. Otherwise add at most
                                    one concise conversational message that moves the discussion forward, asks one focused
                                    question, or responds to the latest thread.

                                    This is only for chatting and thought-management testing. Do not create channels,
                                    create tasks, update project state, inspect repositories, mention implementation status,
                                    or use any room other than %s. If nothing useful should be said this turn,
                                    stay quiet.
                                    """.formatted(ROOM, ROOM, ROOM))
                            .toolProfile(TeamChatToolProfile.ID))
                    .to("direct:act")
                    .process(routine.exitThought("forum-agents-chat-turn"))
                    .delay(5000)
                .end();
    }
}
