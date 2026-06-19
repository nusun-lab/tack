package us.nusun.llmshare.tests.api;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;

@QuarkusTest
class ApiRouteTest {
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
    void resetOnlyResetsTopics() {
                given()
                .when()
                .post("/workspace/reset")
                .then()
                .statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo("RESET"))
                .body("jobsCleared", org.hamcrest.Matchers.equalTo(false))
                .body("topics.size()", org.hamcrest.Matchers.equalTo(3));
    }
}
