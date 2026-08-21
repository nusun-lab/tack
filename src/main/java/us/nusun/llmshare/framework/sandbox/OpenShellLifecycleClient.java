package us.nusun.llmshare.framework.sandbox;

import io.grpc.StatusRuntimeException;
import io.quarkus.grpc.GrpcClient;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import us.nusun.llmshare.framework.sandbox.grpc.DeleteSandboxRequest;
import us.nusun.llmshare.framework.sandbox.grpc.ListSandboxesRequest;
import us.nusun.llmshare.framework.sandbox.grpc.OpenShellGrpc;
import us.nusun.llmshare.framework.sandbox.grpc.Sandbox;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@ApplicationScoped
public class OpenShellLifecycleClient {
    private static final String WORKSPACE = "default";
    private static final int PAGE_SIZE = 100;

    @GrpcClient("openshell")
    OpenShellGrpc.OpenShellBlockingStub stub;

    private final OpenShellGrpcClient openshell;
    private final Duration controlTimeout;

    public OpenShellLifecycleClient(
            OpenShellGrpcClient openshell,
            @ConfigProperty(name = "agent.sandbox.openshell.control-timeout-seconds", defaultValue = "180") long controlTimeoutSeconds) {
        this.openshell = openshell;
        this.controlTimeout = Duration.ofSeconds(controlTimeoutSeconds);
    }

    public List<String> deleteTackSandboxes() {
        if (!openshell.enabled()) {
            return List.of();
        }
        List<String> names = listTackSandboxNames();
        for (String name : names) {
            deleteAndAwait(name);
        }
        return List.copyOf(names);
    }

    private List<String> listTackSandboxNames() {
        List<String> names = new ArrayList<>();
        int offset = 0;
        while (true) {
            List<Sandbox> page;
            try {
                page = controlStub().listSandboxes(ListSandboxesRequest.newBuilder()
                        .setWorkspace(WORKSPACE)
                        .setLimit(PAGE_SIZE)
                        .setOffset(offset)
                        .build()).getSandboxesList();
            } catch (StatusRuntimeException e) {
                throw new IllegalStateException("OpenShell failed to list sandboxes for reset: " + e.getStatus(), e);
            }
            page.stream()
                    .filter(OpenShellLifecycleClient::isTackOwned)
                    .map(sandbox -> sandbox.getMetadata().getName())
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(names::add);
            if (page.size() < PAGE_SIZE) {
                return names;
            }
            offset += page.size();
        }
    }

    private void deleteAndAwait(String name) {
        try {
            controlStub().deleteSandbox(DeleteSandboxRequest.newBuilder()
                    .setName(name)
                    .setWorkspace(WORKSPACE)
                    .build());
        } catch (StatusRuntimeException e) {
            if (e.getStatus().getCode() != io.grpc.Status.Code.NOT_FOUND) {
                throw new IllegalStateException("OpenShell failed to delete sandbox " + name + ": " + e.getStatus(), e);
            }
            return;
        }

        long deadline = System.nanoTime() + controlTimeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (openshell.getSandbox(name).isEmpty()) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted waiting for OpenShell sandbox deletion: " + name, e);
            }
        }
        throw new IllegalStateException("Timed out waiting for OpenShell sandbox deletion: " + name);
    }

    private OpenShellGrpc.OpenShellBlockingStub controlStub() {
        return stub.withDeadlineAfter(controlTimeout.toSeconds(), TimeUnit.SECONDS);
    }

    private static boolean isTackOwned(Sandbox sandbox) {
        if (sandbox == null || !sandbox.hasMetadata()) {
            return false;
        }
        String name = sandbox.getMetadata().getName();
        Map<String, String> labels = sandbox.getMetadata().getLabelsMap();
        if ("tack".equals(labels.get("tack-owner"))) {
            return true;
        }
        return name != null
                && name.startsWith("tack-")
                && labels.containsKey("tack-mission")
                && labels.containsKey("tack-persona");
    }
}
