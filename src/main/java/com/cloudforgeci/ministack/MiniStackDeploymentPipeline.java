package com.cloudforgeci.ministack;

import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalDeploymentNaming;
import com.cloudforge.core.local.LocalDeploymentPipeline;
import com.cloudforge.core.local.LocalDeploymentPipelineResult;
import com.cloudforge.core.local.LocalDeploymentRequest;
import com.cloudforge.core.local.TemplateAdaptationResult;

import java.io.IOException;
import java.nio.file.Path;

/**
 * MiniStack entry point for adapt-and-deploy.
 *
 * <p>Delegates to {@link LocalDeploymentPipeline} with MiniStack-specific wiring.</p>
 */
public final class MiniStackDeploymentPipeline {
    private static final LocalDeploymentPipeline PIPELINE = new LocalDeploymentPipeline(
        MiniStackTemplateAdapter.INSTANCE,
        MiniStackDeployer::new,
        MiniStackLocalRuntime.INSTANCE::reconcile);

    private MiniStackDeploymentPipeline() {
    }

    /** @deprecated use {@link com.cloudforge.core.local.LocalDeploymentArtifactPaths#forTarget} via {@link LocalDeploymentRequest}. */
    @Deprecated
    public record ArtifactPaths(Path canonicalTemplate, Path localTemplate, Path adaptationReport) {
        public static ArtifactPaths inDirectory(Path outputDirectory, String contextStackName) {
            var paths = com.cloudforge.core.local.LocalDeploymentArtifactPaths.forTarget(
                outputDirectory, contextStackName, DeploymentTarget.MINISTACK);
            return new ArtifactPaths(
                paths.canonicalTemplate(), paths.localTemplate(), paths.adaptationReport());
        }
    }

    /** @deprecated use {@link LocalDeploymentRequest}. */
    @Deprecated
    public record DeployRequest(
            String ministackStackName,
            Path canonicalTemplate,
            Path localTemplate,
            Path adaptationReport,
            String contextStackName) {

        public static DeployRequest of(
                String contextStackName,
                Path canonicalTemplate,
                Path outputDirectory) {
            LocalDeploymentRequest request = LocalDeploymentRequest.forTarget(
                DeploymentTarget.MINISTACK,
                contextStackName,
                canonicalTemplate,
                outputDirectory);
            return new DeployRequest(
                request.localStackName(),
                request.canonicalTemplate(),
                request.localTemplate(),
                request.adaptationReport(),
                request.contextStackName());
        }

        LocalDeploymentRequest toCoreRequest() {
            return new LocalDeploymentRequest(
                DeploymentTarget.MINISTACK,
                ministackStackName,
                canonicalTemplate,
                localTemplate,
                adaptationReport,
                contextStackName);
        }
    }

    /** @deprecated use {@link LocalDeploymentPipelineResult}. */
    @Deprecated
    public record DeployResult(
            TemplateAdaptationResult adaptation,
            com.cloudforge.core.local.LocalDeployResult deployment) {
    }

    public static String toMinistackStackName(String stackName) {
        return LocalDeploymentNaming.localStackName(stackName, DeploymentTarget.MINISTACK);
    }

    public static TemplateAdaptationResult adapt(DeployRequest request) throws IOException {
        return PIPELINE.adapt(request.toCoreRequest());
    }

    public static TemplateAdaptationResult adapt(LocalDeploymentRequest request) throws IOException {
        return PIPELINE.adapt(request);
    }

    public static DeployResult deploy(DeployRequest request) throws IOException {
        return fromCore(PIPELINE.deploy(request.toCoreRequest()));
    }

    public static LocalDeploymentPipelineResult deploy(LocalDeploymentRequest request)
            throws IOException {
        return PIPELINE.deploy(request);
    }

    public static LocalDeploymentRequest request(
            String contextStackName,
            Path canonicalTemplate,
            Path outputDirectory) {
        return LocalDeploymentRequest.forTarget(
            DeploymentTarget.MINISTACK,
            contextStackName,
            canonicalTemplate,
            outputDirectory);
    }

    private static DeployResult fromCore(LocalDeploymentPipelineResult result) {
        return new DeployResult(result.adaptation(), result.deployment());
    }
}
