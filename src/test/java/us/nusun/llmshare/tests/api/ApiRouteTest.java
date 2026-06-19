package us.nusun.llmshare.tests.api;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import us.nusun.llmshare.bean.ProjectMemoryStore;
import us.nusun.llmshare.model.DirectorStateRecord;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;

@QuarkusTest
class ApiRouteTest {
    @Inject
    ProjectMemoryStore projectMemoryStore;

    @BeforeEach
    void clear() {
        projectMemoryStore.clear();
    }

    @Test
    void postGoalsReturnsDirectorAssignments() {
        given()
                .contentType("application/json")
                .body("""
                        {
                          "goal": "We need a basic internal baby-tracking web app for feeds, diapers, sleep, and wake events. Give the team one concise mission each so they can start work today."
                        }
                        """)
                .when()
                .post("/goals")
                .then()
                .statusCode(200)
                .body("'software-team/project-manager'", not(blankOrNullString()))
                .body("'software-team/product-owner'", not(blankOrNullString()))
                .body("'software-team/application-developer'", not(blankOrNullString()))
                .body("'software-team/project-manager'", containsString("Coordinate"))
                .body("'software-team/product-owner'", containsString("Clarify"))
                .body("'software-team/application-developer'", containsString("Drive"));
    }

    @Test
    void resumeStartsRequestedGoalByRequestId() {
        projectMemoryStore.applyDirectorState(new DirectorStateRecord(
                "11111111-1111-1111-1111-111111111111",
                "Resume the parking lot manager app.",
                java.util.Map.of("software-team/project-manager", "Coordinate delivery."),
                "2026-06-19T00:00:00Z"));

        given()
                .queryParam("requestId", "11111111-1111-1111-1111-111111111111")
                .when()
                .post("/goals/resume")
                .then()
                .statusCode(200)
                .body("status", equalTo("RESUMED"))
                .body("resumedCount", equalTo(1))
                .body("requestIds", hasItems("11111111-1111-1111-1111-111111111111"));
    }

    @Test
    void resetCanTargetSingleRequestId() {
        projectMemoryStore.applyDirectorState(new DirectorStateRecord(
                "req-1",
                "First goal",
                java.util.Map.of(),
                "2026-06-19T00:00:00Z"));
        projectMemoryStore.applyDirectorState(new DirectorStateRecord(
                "req-2",
                "Second goal",
                java.util.Map.of(),
                "2026-06-19T00:00:01Z"));

        given()
                .queryParam("requestId", "req-1")
                .when()
                .post("/workspace/reset")
                .then()
                .statusCode(200)
                .body("status", equalTo("RESET"))
                .body("scope", equalTo("REQUEST"))
                .body("requestId", equalTo("req-1"));

        given()
                .when()
                .get("/workspace/view")
                .then()
                .statusCode(200)
                .body("projects.requestId", not(hasItems("req-1")))
                .body("projects.requestId", hasItems("req-2"));
    }

    @Test
    void resetOnlyResetsTopics() {
                given()
                .when()
                .post("/workspace/reset")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("RESET"))
                .body("jobsCleared", org.hamcrest.Matchers.equalTo(false))
                .body("topics.size()", org.hamcrest.Matchers.equalTo(6));
    }
}
