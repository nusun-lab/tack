package us.nusun.llmshare.tests.api;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.camel.CamelContext;
import org.apache.camel.Exchange;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.bean.AgentActivityTracker;
import us.nusun.llmshare.bean.ProjectMemoryStore;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasValue;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;

@QuarkusTest
class WorkspaceViewApiTest {
    @Inject
    CamelContext camelContext;

    @Inject
    ProjectMemoryStore projectMemoryStore;

    @Inject
    AgentActivityTracker agentActivityTracker;

    @BeforeEach
    void clearStore() {
        projectMemoryStore.clear();
        agentActivityTracker.clear();
    }

    @Test
    void workspaceViewReturnsBasicLiveStateShape() {
        given()
                .when()
                .get("/workspace/view")
                .then()
                .statusCode(200)
                .body("timestamp", not(blankOrNullString()))
                .body("routes.size()", org.hamcrest.Matchers.greaterThan(0))
                .body("topics.size()", org.hamcrest.Matchers.greaterThan(0))
                .body("$", hasKey("topicDetails"))
                .body("$", hasKey("summary"))
                .body("$", hasKey("teamRegistry"))
                .body("$", hasKey("teamContext"))
                .body("summary", hasKey("activePersonas"))
                .body("scratchpads.size()", org.hamcrest.Matchers.greaterThanOrEqualTo(0))
                .body("projects.size()", org.hamcrest.Matchers.greaterThanOrEqualTo(0))
                .body("teamRegistry.roleId", hasItems(
                        "software-team/director",
                        "software-team/project-manager",
                        "software-team/product-owner",
                        "software-team/application-developer",
                        "software-team/test-observer"
                ))
                .body("teamRegistry.find { it.roleId == 'software-team/project-manager' }.activeNow", equalTo(false))
                .body("teamRegistry.find { it.roleId == 'software-team/project-manager' }.loopStatus", equalTo("Idle"))
                .body("teamRegistry.find { it.roleId == 'software-team/test-observer' }.scratchpadSummary", equalTo("No scratchpad yet"))
                .body("teamRegistry.find { it.roleId == 'software-team/test-observer' }.platformIdentities", hasValue("@test-observer"));
    }

    @Test
    void workspaceViewReturnsAllScratchpads() {
        putScratchpad("req-1", "software-team/project-manager", "pm-note");
        putScratchpad("req-1", "software-team/product-owner", "po-note");
        putScratchpad("req-1", "software-team/application-developer", "dev-note");

        given()
                .when()
                .get("/workspace/view")
                .then()
                .statusCode(200)
                .body("scratchpads.roleId", hasItems(
                        "software-team/project-manager",
                        "software-team/product-owner",
                        "software-team/application-developer"
                ))
                .body("scratchpads.roleId", not(hasItems("software-team/routine-formatter")))
                .body("scratchpads.find { it.roleId == 'software-team/project-manager' && it.requestId == 'req-1' }.requestId", equalTo("req-1"))
                .body("scratchpads.find { it.roleId == 'software-team/project-manager' && it.requestId == 'req-1' }.length", equalTo("pm-note".length()))
                .body("scratchpads.find { it.roleId == 'software-team/project-manager' && it.requestId == 'req-1' }.preview", equalTo("pm-note"))
                .body("scratchpads.find { it.roleId == 'software-team/project-manager' && it.requestId == 'req-1' }.fullText", equalTo("pm-note"))
                .body("scratchpads.find { it.roleId == 'software-team/project-manager' && it.requestId == 'req-1' }.messageCount", equalTo(1))
                .body("scratchpads.scratchpad", hasItems("pm-note", "po-note", "dev-note"));
    }

    @Test
    void workspaceViewSurfacesActiveAgentLoopState() {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty("requestId", "req-live");
        exchange.setProperty("currentStep", "chat-channel-setup");
        exchange.setProperty("currentRoutine", "Create or confirm the private Mattermost project channel.");
        exchange.setProperty("cycleCount", 2);
        exchange.setProperty("dayCount", 1);
        exchange.getIn().setHeader("agent", "software-team/project-manager");
        agentActivityTracker.markWakeup(exchange);
        agentActivityTracker.markStep(exchange);

        given()
                .when()
                .get("/workspace/view")
                .then()
                .statusCode(200)
                .body("summary.activePersonas", equalTo(1))
                .body("teamRegistry.find { it.roleId == 'software-team/project-manager' }.activeNow", equalTo(true))
                .body("teamRegistry.find { it.roleId == 'software-team/project-manager' }.loopStatus", equalTo("Active"))
                .body("teamRegistry.find { it.roleId == 'software-team/project-manager' }.activeRequestId", equalTo("req-live"))
                .body("teamRegistry.find { it.roleId == 'software-team/project-manager' }.currentStep", equalTo("chat-channel-setup"))
                .body("teamRegistry.find { it.roleId == 'software-team/project-manager' }.activeCycleCount", equalTo(2))
                .body("teamRegistry.find { it.roleId == 'software-team/project-manager' }.activeDayCount", equalTo(1));
    }

    private void putScratchpad(String requestId, String roleId, String value) {
        Exchange exchange = new DefaultExchange(camelContext);
        exchange.setProperty("requestId", requestId);
        projectMemoryStore.putAgentScratchpad(exchange, roleId, value);
    }
}
