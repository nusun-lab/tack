package us.nusun.tack.framework.tests.component;

import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.junit.jupiter.api.Test;
import us.nusun.tack.framework.inventory.PersonaCatalog;
import us.nusun.tack.framework.inventory.RoutineCatalog;
import us.nusun.tack.framework.model.AgentRunRequest;
import us.nusun.tack.framework.model.Mission;
import us.nusun.tack.framework.model.MissionRequest;
import us.nusun.tack.framework.model.Persona;
import us.nusun.tack.framework.sandbox.OpenShellGrpcClient;
import us.nusun.tack.framework.sandbox.PersonaSandboxManager;
import us.nusun.tack.framework.sandbox.grpc.AttachSandboxProviderRequest;
import us.nusun.tack.framework.sandbox.grpc.AttachSandboxProviderResponse;
import us.nusun.tack.framework.sandbox.grpc.CreateProviderRequest;
import us.nusun.tack.framework.sandbox.grpc.CreateSandboxRequest;
import us.nusun.tack.framework.sandbox.grpc.ExecSandboxEvent;
import us.nusun.tack.framework.sandbox.grpc.ExecSandboxExit;
import us.nusun.tack.framework.sandbox.grpc.ExecSandboxRequest;
import us.nusun.tack.framework.sandbox.grpc.GetProviderProfileRequest;
import us.nusun.tack.framework.sandbox.grpc.GetProviderRequest;
import us.nusun.tack.framework.sandbox.grpc.GetSandboxPolicyStatusRequest;
import us.nusun.tack.framework.sandbox.grpc.GetSandboxPolicyStatusResponse;
import us.nusun.tack.framework.sandbox.grpc.GetSandboxRequest;
import us.nusun.tack.framework.sandbox.grpc.NetworkEndpoint;
import us.nusun.tack.framework.sandbox.grpc.ObjectMeta;
import us.nusun.tack.framework.sandbox.grpc.OpenShellGrpc;
import us.nusun.tack.framework.sandbox.grpc.PolicyStatus;
import us.nusun.tack.framework.sandbox.grpc.Provider;
import us.nusun.tack.framework.sandbox.grpc.ProviderProfile;
import us.nusun.tack.framework.sandbox.grpc.ProviderProfileResponse;
import us.nusun.tack.framework.sandbox.grpc.ProviderResponse;
import us.nusun.tack.framework.sandbox.grpc.Sandbox;
import us.nusun.tack.framework.sandbox.grpc.SandboxCondition;
import us.nusun.tack.framework.sandbox.grpc.SandboxPhase;
import us.nusun.tack.framework.sandbox.grpc.SandboxPolicyRevision;
import us.nusun.tack.framework.sandbox.grpc.SandboxResponse;
import us.nusun.tack.framework.sandbox.grpc.SandboxStatus;
import us.nusun.tack.framework.sandbox.grpc.SandboxStreamEvent;
import us.nusun.tack.framework.sandbox.grpc.UpdateConfigRequest;
import us.nusun.tack.framework.sandbox.grpc.UpdateConfigResponse;
import us.nusun.tack.framework.sandbox.grpc.UpdateProviderRequest;
import us.nusun.tack.framework.sandbox.grpc.WatchSandboxRequest;
import us.nusun.tack.framework.state.AgentRunStore;
import us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport;
import us.nusun.tack.framework.tests.support.FrameworkFixtures;
import us.nusun.tack.framework.tests.support.StubKafkaComponent;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static us.nusun.tack.framework.tests.support.AgentRunRouteTestSupport.SCHEDULED_TOPIC;

class SandboxAdmissionTest {
    @Test
    void initialAndLaterAdmissionsWaitForReadinessPolicyAndGit() throws Exception {
        for (boolean initial : List.of(true, false)) {
            Gateway gateway = new Gateway();
            gateway.gatePreparation = true;
            try (var fixture = new Fixture(gateway, 10);
                 var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var request = executor.submit(() -> fixture.admit(initial));
                try {
                    gateway.readiness.awaitEntry();
                    assertTrue(fixture.kafka.sentTopics().isEmpty());
                    assertFalse(request.isDone());
                    gateway.readiness.release();

                    gateway.packages.awaitEntry();
                    assertTrue(fixture.kafka.sentTopics().isEmpty());
                    assertFalse(request.isDone());
                    gateway.packages.release();

                    gateway.git.awaitEntry();
                    assertTrue(fixture.kafka.sentTopics().isEmpty());
                    assertFalse(request.isDone());
                    gateway.git.release();

                    gateway.maven.awaitEntry();
                    assertTrue(fixture.kafka.sentTopics().isEmpty());
                    assertFalse(request.isDone());
                } finally {
                    gateway.readiness.release();
                    gateway.packages.release();
                    gateway.git.release();
                    gateway.maven.release();
                }
                assertNull(request.get(5, TimeUnit.SECONDS).getException());
                assertEquals(List.of(SCHEDULED_TOPIC), fixture.kafka.sentTopics());
            }
        }
    }

    @Test
    void readinessTimeoutAcrossReconnectRetainsSandboxForRetry() throws Exception {
        Gateway gateway = new Gateway();
        gateway.holdReadiness = true;
        gateway.disconnectFirstWatch = true;
        try (var fixture = new Fixture(gateway, 1)) {
            assertFailure(fixture, true, "stage=readiness", "timeoutSeconds=1", "ImagePull");
            assertEquals(2, gateway.watches.get());
            assertTrue(gateway.secondWatchBudget.get() < gateway.firstWatchBudget.get());
            assertEquals(1, gateway.creates.get());
            assertEquals(Set.of("unrelated"), gateway.rules);

            gateway.holdReadiness = false;
            assertNull(fixture.admit(true).getException());
            assertEquals(List.of(SCHEDULED_TOPIC), fixture.kafka.sentTopics());
            assertEquals(1, gateway.creates.get());
            assertEquals(3, gateway.watches.get(), "Retry must watch the existing provisioning sandbox");
            assertEquals(Set.of("unrelated", "tack-developer-packages", "tack-gitea"), gateway.rules);
            assertEquals(1, gateway.gitExecutions.get());

            assertNull(fixture.admit(false).getException());
            assertEquals(1, gateway.creates.get());
            assertEquals(3, gateway.watches.get(), "An already-ready recovery must not start a watch");
            assertEquals(1, gateway.providerCreations.get());
            assertEquals(0, gateway.attachments.get(), "The provider was attached at creation");
            assertEquals(2, gateway.gitExecutions.get());
            assertEquals(Set.of("unrelated", "tack-developer-packages", "tack-gitea"), gateway.rules);
        }
    }

    @Test
    void terminalPhaseAndPolicyOrGitFailuresPreventScheduling() throws Exception {
        Gateway gateway = new Gateway();
        try (var fixture = new Fixture(gateway, 2)) {
            gateway.terminal = true;
            assertFailure(fixture, true, "stage=readiness", "SANDBOX_PHASE_ERROR");
            assertEquals(0, gateway.watches.get());

            gateway.markReady();
            gateway.failPolicy = true;
            assertFailure(fixture, true, "stage=developer package policy", "test policy rejection");
            assertEquals(0, gateway.gitExecutions.get());

            gateway.failPolicy = false;
            gateway.gitExitCode = 124;
            assertFailure(fixture, false, "stage=Git identity and credentials", "exit=124");
        }
    }

    private static void assertFailure(Fixture fixture, boolean initial, String... details) {
        Exception error = fixture.admit(initial).getException();
        assertNotNull(error);
        for (String detail : details) {
            assertTrue(error.getMessage().contains(detail), error.getMessage());
        }
        assertTrue(fixture.kafka.sentTopics().isEmpty());
        assertTrue(fixture.store.runs().isEmpty());
    }

    private static final class Fixture implements AutoCloseable {
        private final Server server;
        private final ManagedChannel channel;
        private final AgentRunStore store = new AgentRunStore();
        private final StubKafkaComponent kafka = new StubKafkaComponent();
        private final DefaultCamelContext camel;
        private final AgentRunRequest run =
                new AgentRunRequest("developer", FrameworkFixtures.ROUTINE_A, Map.of("repositoryUrl", "https://git.example/project"));
        private final Mission mission = new Mission("sandbox-test", "Build", List.of(), "active", Instant.now());

        private Fixture(Gateway gateway, long provisionSeconds) throws Exception {
            server = ServerBuilder.forPort(0).addService(gateway).build().start();
            channel = ManagedChannelBuilder.forAddress("127.0.0.1", server.getPort()).usePlaintext().build();
            var openshell = new OpenShellGrpcClient(true, 4, provisionSeconds, 4, "test-image");
            FrameworkFixtures.set(openshell, "stub", OpenShellGrpc.newBlockingStub(channel));

            var developer = Persona.builder("developer").name("Developer").identity("gitea", "test-token", "dev").build();
            var personas = new PersonaCatalog(List.of(developer));
            var routines = new RoutineCatalog(List.of(AgentRunRouteTestSupport.routine(
                    FrameworkFixtures.ROUTINE_A, "direct:fixture-routine")));
            var projections = FrameworkFixtures.projectionManager(FrameworkFixtures.projectionStore());
            FrameworkFixtures.set(projections, "personas", personas);
            FrameworkFixtures.set(projections, "routines", routines);
            camel = AgentRunRouteTestSupport.context(projections, routines, store, kafka, "sandbox-test", personas,
                    new PersonaSandboxManager(openshell, null, null, ""));
            camel.start();
        }

        private Exchange admit(boolean initial) {
            return camel.createProducerTemplate().request(initial ? "direct:start-mission" : "direct:start-agent-run", exchange -> {
                if (initial) {
                    exchange.getIn().setHeader("requestId", mission.id());
                    exchange.getIn().setBody(new MissionRequest(mission.goal(), List.of(run)));
                } else {
                    exchange.setProperty("mission", mission);
                    exchange.getIn().setBody(run);
                }
            });
        }

        @Override
        public void close() throws IOException {
            try {
                camel.close();
            } finally {
                channel.shutdownNow();
                server.shutdownNow();
            }
        }
    }

    private static final class Gate {
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch released = new CountDownLatch(1);

        void block() {
            entered.countDown();
            await(released);
        }

        void awaitEntry() {
            await(entered);
        }

        void release() {
            released.countDown();
        }

        private void await(CountDownLatch latch) {
            try {
                assertTrue(latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
        }
    }

    private static final class Gateway extends OpenShellGrpc.OpenShellImplBase {
        private final Gate readiness = new Gate();
        private final Gate packages = new Gate();
        private final Gate git = new Gate();
        private final Gate maven = new Gate();
        private final AtomicInteger creates = new AtomicInteger();
        private final AtomicInteger watches = new AtomicInteger();
        private final AtomicInteger gitExecutions = new AtomicInteger();
        private final AtomicInteger providerCreations = new AtomicInteger();
        private final AtomicInteger attachments = new AtomicInteger();
        private final AtomicLong firstWatchBudget = new AtomicLong();
        private final AtomicLong secondWatchBudget = new AtomicLong();
        private final Set<String> rules = ConcurrentHashMap.newKeySet();
        private volatile Sandbox sandbox;
        private volatile Provider provider;
        private volatile boolean gatePreparation;
        private volatile boolean holdReadiness;
        private volatile boolean disconnectFirstWatch;
        private volatile boolean terminal;
        private volatile boolean failPolicy;
        private volatile int gitExitCode;

        private Gateway() {
            rules.add("unrelated");
        }

        @Override
        public void getSandbox(GetSandboxRequest request, StreamObserver<SandboxResponse> response) {
            Sandbox current = sandbox;
            if (current == null) {
                response.onError(Status.NOT_FOUND.asRuntimeException());
            } else {
                send(response, SandboxResponse.newBuilder().setSandbox(current).build());
            }
        }

        @Override
        public void createSandbox(CreateSandboxRequest request, StreamObserver<SandboxResponse> response) {
            creates.incrementAndGet();
            sandbox = Sandbox.newBuilder()
                    .setMetadata(ObjectMeta.newBuilder().setId("gateway-id").setName(request.getName()))
                    .setSpec(request.getSpec())
                    .setStatus(SandboxStatus.newBuilder()
                            .setPhase(terminal ? SandboxPhase.SANDBOX_PHASE_ERROR : SandboxPhase.SANDBOX_PHASE_PROVISIONING)
                            .addConditions(SandboxCondition.newBuilder().setType("Ready").setReason("ImagePull")))
                    .build();
            send(response, SandboxResponse.newBuilder().setSandbox(sandbox).build());
        }

        @Override
        public void watchSandbox(WatchSandboxRequest request, StreamObserver<SandboxStreamEvent> response) {
            long remaining = Context.current().getDeadline().timeRemaining(TimeUnit.MILLISECONDS);
            if (watches.incrementAndGet() == 1) {
                firstWatchBudget.set(remaining);
                if (disconnectFirstWatch) {
                    response.onError(Status.UNAVAILABLE.asRuntimeException());
                    return;
                }
            } else {
                secondWatchBudget.set(remaining);
            }
            response.onNext(SandboxStreamEvent.newBuilder().setSandbox(sandbox).build());
            if (holdReadiness) {
                return;
            }
            if (gatePreparation) {
                readiness.block();
            }
            markReady();
            send(response, SandboxStreamEvent.newBuilder().setSandbox(sandbox).build());
        }

        private void markReady() {
            sandbox = sandbox.toBuilder()
                    .setStatus(SandboxStatus.newBuilder().setPhase(SandboxPhase.SANDBOX_PHASE_READY))
                    .build();
        }

        @Override
        public void getProviderProfile(GetProviderProfileRequest request, StreamObserver<ProviderProfileResponse> response) {
            send(response, ProviderProfileResponse.newBuilder().setProfile(ProviderProfile.newBuilder()
                    .addEndpoints(NetworkEndpoint.newBuilder().setHost("git.example").setPort(443))).build());
        }

        @Override
        public void getProvider(GetProviderRequest request, StreamObserver<ProviderResponse> response) {
            if (provider == null) {
                response.onError(Status.NOT_FOUND.asRuntimeException());
            } else {
                send(response, ProviderResponse.newBuilder().setProvider(provider).build());
            }
        }

        @Override
        public void createProvider(CreateProviderRequest request, StreamObserver<ProviderResponse> response) {
            providerCreations.incrementAndGet();
            provider = request.getProvider();
            send(response, ProviderResponse.newBuilder().setProvider(provider).build());
        }

        @Override
        public void updateProvider(UpdateProviderRequest request, StreamObserver<ProviderResponse> response) {
            provider = request.getProvider();
            send(response, ProviderResponse.newBuilder().setProvider(provider).build());
        }

        @Override
        public void attachSandboxProvider(AttachSandboxProviderRequest request, StreamObserver<AttachSandboxProviderResponse> response) {
            attachments.incrementAndGet();
            response.onError(Status.FAILED_PRECONDITION.withDescription("duplicate attachment").asRuntimeException());
        }

        @Override
        public void updateConfig(UpdateConfigRequest request, StreamObserver<UpdateConfigResponse> response) {
            request.getMergeOperationsList().forEach(operation -> rules.add(operation.getAddRule().getRuleName()));
            send(response, UpdateConfigResponse.newBuilder().setVersion(rules.size()).build());
        }

        @Override
        public void getSandboxPolicyStatus(GetSandboxPolicyStatusRequest request, StreamObserver<GetSandboxPolicyStatusResponse> response) {
            if (gatePreparation && !rules.contains("tack-gitea")) {
                packages.block();
            }
            var revision = SandboxPolicyRevision.newBuilder().setStatus(PolicyStatus.POLICY_STATUS_LOADED);
            if (failPolicy) {
                revision.setStatus(PolicyStatus.POLICY_STATUS_FAILED).setLoadError("test policy rejection");
            }
            send(response, GetSandboxPolicyStatusResponse.newBuilder().setRevision(revision).build());
        }

        @Override
        public void execSandbox(ExecSandboxRequest request, StreamObserver<ExecSandboxEvent> response) {
            if (!request.getCommand(2).contains("git config --global")) {
                assertEquals(List.of("/bin/sh", "-lc"), request.getCommandList().subList(0, 2));
                assertEquals("/sandbox/.m2", request.getEnvironmentOrThrow("MAVEN_USER_HOME"));
                assertEquals("-Dmaven.repo.local=/sandbox/.m2/repository", request.getEnvironmentOrThrow("MAVEN_OPTS"));
                assertTrue(request.getCommand(2).contains("javac -version"));
                if (gatePreparation) {
                    maven.block();
                }
                send(response, ExecSandboxEvent.newBuilder().setExit(ExecSandboxExit.getDefaultInstance()).build());
                return;
            }
            gitExecutions.incrementAndGet();
            if (gatePreparation) {
                git.block();
            }
            send(response, ExecSandboxEvent.newBuilder().setExit(ExecSandboxExit.newBuilder().setExitCode(gitExitCode)).build());
        }

        private static <T> void send(StreamObserver<T> response, T value) {
            response.onNext(value);
            response.onCompleted();
        }
    }
}
