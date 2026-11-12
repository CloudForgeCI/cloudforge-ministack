package com.cloudforgeci.ministack;

import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalDeployResult;
import com.cloudforge.core.local.LocalDeploymentPipeline;
import com.cloudforge.core.local.LocalDeploymentPipelineResult;
import com.cloudforge.core.local.LocalDeploymentRequest;
import com.cloudforge.core.local.PreflightMode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.net.URI;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/** Runs the agent's operations against the MiniStack at {@code AWS_ENDPOINT_URL}. */
final class MiniStackAgentBackend implements MiniStackAgent.Backend {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String LOCAL_SUFFIX = "-ministack";

    private final Path dataDir;

    MiniStackAgentBackend(Path dataDir) {
        this.dataDir = dataDir;
    }

    @Override
    public MiniStackAgent.Outcome deploy(MiniStackAgent.DeployRequest request) throws IOException {
        Files.createDirectories(dataDir);
        Path workDir = Files.createTempDirectory(dataDir, "deploy-");
        try {
            Path canonical = workDir.resolve(request.stackName() + ".template.json");
            Files.writeString(canonical, MAPPER.writeValueAsString(request.template()));
            // The same template checks `cloudforge-cli deploy` runs, so a blueprint MiniStack cannot host is
            // refused with the reason before anything is created.
            MiniStackDeployPreflight.validate(
                    null, null, canonical, URI.create(MiniStackDeployer.resolveEndpoint()), request.stackName())
                .throwIfBlocked(PreflightMode.ENFORCE);
            LocalDeploymentRequest local = LocalDeploymentRequest.forTarget(
                DeploymentTarget.MINISTACK, request.stackName(), canonical, workDir);
            if (request.replaceSameApplication() && request.applicationId() != null) {
                try (MiniStackDeployer deployer = deployer(request.region())) {
                    deployer.replaceSameApplicationStacks(
                        request.applicationId(), local.localStackName(), dataDir.resolve("deployment-contexts"));
                }
            }
            LocalDeploymentPipelineResult result = LocalDeploymentPipeline.forRegion(
                    MiniStackTemplateAdapter.INSTANCE,
                    this::deployer,
                    MiniStackLocalRuntime.INSTANCE::reconcile)
                .deploy(local, request.region());
            LocalDeployResult deployment = result.deployment();
            return new MiniStackAgent.Outcome(
                deployment.outputs(),
                deployment.changes().stream()
                    .map(change -> change.action() + " " + change.resourceType() + " " + change.logicalResourceId())
                    .toList(),
                deployment.created(),
                deployment.noOp());
        } finally {
            deleteRecursively(workDir);
        }
    }

    @Override
    public void delete(String stackName, String region) throws IOException {
        String localName = stackName.endsWith(LOCAL_SUFFIX)
            ? stackName : MiniStackDeploymentPipeline.toMinistackStackName(stackName);
        try (MiniStackDeployer deployer = deployer(region)) {
            deployer.delete(localName);
        }
    }

    private MiniStackDeployer deployer(String region) {
        return new MiniStackDeployer(MiniStackDeployer.resolveEndpoint(), region);
    }

    private static void deleteRecursively(Path directory) throws IOException {
        try (Stream<Path> paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
