package us.nusun.tack.framework.tests.unit;

import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.model.Mission;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.sandbox.OpenShellGrpcClient;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;
import us.nusun.tack.framework.state.AgentRun;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PersonaSandboxManagerTest {

    @Test
    void reusesOneSandboxAcrossAgentRunsForTheSameMissionAndPersona() {
        FakeOpenShellClient openshell = new FakeOpenShellClient();
        PersonaSandboxManager manager = new PersonaSandboxManager(openshell, null, null, "");
        Mission mission = mission("mission-a");
        Persona persona = persona("persona-a");
        AgentRun first = run("run-1", mission, persona);
        AgentRun second = run("run-2", mission, persona);

        String firstName = manager.ensureReady(mission, persona, first).name();
        String secondName = manager.ensureReady(mission, persona, second).name();

        assertEquals(firstName, secondName);
        assertEquals(firstName, first.workingState().get(PersonaSandboxManager.WORKING_STATE_SANDBOX_NAME));
        assertEquals(firstName, second.workingState().get(PersonaSandboxManager.WORKING_STATE_SANDBOX_NAME));
        assertEquals(1, openshell.createCalls);
    }

    @Test
    void sandboxIdentityChangesWithMissionOrPersona() {
        PersonaSandboxManager manager = new PersonaSandboxManager(new FakeOpenShellClient(), null, null, "");

        String base = manager.sandboxNameFor("mission-a", "persona-a");

        assertNotEquals(base, manager.sandboxNameFor("mission-b", "persona-a"));
        assertNotEquals(base, manager.sandboxNameFor("mission-a", "persona-b"));
        assertNull(manager.sandboxNameFor("", "persona-a"));
        assertNull(manager.sandboxNameFor("mission-a", null));
    }

    private static Mission mission(String id) {
        return new Mission(id, "Test mission", List.of(), "active", Map.of(), Instant.now());
    }

    private static Persona persona(String id) {
        return Persona.builder(id).name(id).build();
    }

    private static AgentRun run(String id, Mission mission, Persona persona) {
        return new AgentRun(id, mission.id(), persona.id(), "routine-a", Map.of());
    }

    private static final class FakeOpenShellClient extends OpenShellGrpcClient {
        private int createCalls;

        private FakeOpenShellClient() {
            super(true, 1, 1, "test-sandbox-image");
        }

        @Override
        public boolean enabled() {
            return true;
        }

        @Override
        public Optional<us.nusun.tack.framework.sandbox.grpc.Sandbox> getSandbox(String name) {
            return Optional.empty();
        }

        @Override
        public us.nusun.tack.framework.sandbox.grpc.Sandbox createSandboxReady(
                String name, List<String> providers, Map<String, String> labels) {
            createCalls++;
            return null;
        }
    }
}
