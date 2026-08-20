package us.nusun.llmshare.example.routine;

import jakarta.enterprise.context.ApplicationScoped;
import org.apache.camel.builder.RouteBuilder;
import us.nusun.llmshare.example.tool.SourceControlToolProfile;
import us.nusun.llmshare.framework.inventory.RoutineProvider;
import us.nusun.llmshare.framework.model.RoutineDefinition;
import us.nusun.llmshare.framework.tack.TackHeaders;

import java.util.List;

@ApplicationScoped
public class SandboxGitTestRoutine extends RouteBuilder implements RoutineProvider {
    public static final String ID = "software-team/sandbox-git-test";

    @Override
    public List<RoutineDefinition> routines() {
        return List.of(RoutineDefinition.builder(ID)
                .name("Sandbox Git Test")
                .main("Test-drive persistent OpenShell development by cloning, editing, validating, committing, and pushing a repository from the persona sandbox.",
                        "direct:example-software-team-sandbox-git-test")
                .defaultLane("high-pass")
                .build());
    }

    @Override
    public void configure() {
        from("direct:example-software-team-sandbox-git-test")
                .routeId("example-software-team-sandbox-git-test")
                .setHeader(TackHeaders.THOUGHT_PURPOSE, constant("""
                        Sandbox git test state: repository URL, local checkout path, requested change, validation evidence,
                        git diff/status, commit, and push result.
                        """))
                .to("tack:thought-enter?name=sandbox-git-test&maxTokens=800")
                .setHeader(TackHeaders.PROMPT, simple("""
                        Test the real developer sandbox against this request:
                        ${body}

                        Perform the work using sandbox_exec for all repository and source-file operations. Work under
                        /sandbox/repos. Clone the repository with ordinary git if it is not already present. Inspect the
                        checkout, make the requested source change, run the most relevant available validation, inspect
                        git diff and status, commit the change, and push it with ordinary git.

                        The sandbox is persistent for this persona and mission and its git identity/credential is prepared
                        by Tack. Do not use Gitea file-content APIs to mutate source code. Source-control tools may be used
                        only for repository metadata if needed. Do not claim success unless git status/diff and the push
                        result provide evidence.
                        """))
                .to("tack:act?id=sandbox-git-test&toolProfile=" + SourceControlToolProfile.ID)
                .to("tack:thought-exit?name=sandbox-git-test");
    }
}
