package us.nusun.llmshare.framework.tests.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

/** Loads and structurally validates contract scenario resources. */
public final class ContractScenarioLoader {
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory()).findAndRegisterModules();

    private ContractScenarioLoader() {
    }

    public static ContractScenario load(String resource) {
        try (InputStream input = ContractScenarioLoader.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalArgumentException("Contract scenario resource not found: " + resource);
            }
            ContractScenario scenario = YAML.readValue(input, ContractScenario.class);
            validate(scenario, resource);
            return scenario;
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to load contract scenario " + resource, e);
        }
    }

    private static void validate(ContractScenario scenario, String resource) {
        require(scenario != null, resource, "document is empty");
        require(text(scenario.scenario()), resource, "scenario is required");
        require(scenario.given() != null, resource, "given is required");
        require(scenario.given().plane() != null, resource, "given.plane is required");
        require(scenario.given().gitea() != null, resource, "given.gitea is required");
        Map<String, ContractScenario.Implementation> implementations = scenario.given().plane().implementations();
        Map<String, ContractScenario.PullRequest> pullRequests = scenario.given().gitea().pullRequests();
        require(implementations != null, resource, "given.plane.implementations is required");
        require(pullRequests != null, resource, "given.gitea.pullRequests is required");

        for (Map.Entry<String, ContractScenario.Implementation> entry : implementations.entrySet()) {
            ContractScenario.Implementation implementation = entry.getValue();
            require(implementation != null, resource, "implementation " + entry.getKey() + " has no facts");
            require(text(implementation.state()), resource, "implementation " + entry.getKey() + " needs state");
            if (implementation.handoff() != null && implementation.handoff().pullRequest() != null) {
                require(pullRequests.containsKey(implementation.handoff().pullRequest()), resource,
                        "implementation " + entry.getKey() + " references unknown pull request "
                                + implementation.handoff().pullRequest());
            }
        }

        require(scenario.when() != null, resource, "when is required");
        require(text(scenario.when().routine()), resource, "when.routine is required");
        require(text(scenario.when().entrypoint()), resource, "when.entrypoint is required");
        require(scenario.then() != null, resource, "then is required");
        require(!list(scenario.then().eventually()).isEmpty(), resource, "then.eventually is required");
        require(scenario.then().limits() != null, resource, "then.limits is required");
        require(scenario.then().limits().productiveIterations() != null
                        && scenario.then().limits().productiveIterations() > 0,
                resource, "then.limits.productiveIterations must be positive");
    }

    private static boolean text(String value) {
        return value != null && !value.isBlank();
    }

    private static <T> List<T> list(List<T> value) {
        return value == null ? List.of() : value;
    }

    private static void require(boolean condition, String resource, String message) {
        if (!condition) {
            throw new IllegalArgumentException(resource + ": " + message);
        }
    }
}
