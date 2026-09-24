package com.cloudforgeci.ministack;

import com.cloudforge.core.config.DeploymentConfig;
import com.cloudforge.core.enums.TopologyType;
import com.cloudforge.core.interfaces.ApplicationSpec;
import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalDeploymentNaming;
import com.cloudforge.core.local.LocalHostPortConflictChecker;
import com.cloudforge.core.local.MiniStackCfnResourceCatalog;
import com.cloudforge.core.local.PreflightResult;
import com.cloudforge.core.local.PreflightSeverity;
import com.cloudforge.core.local.PreflightViolation;
import com.cloudforge.core.local.TemplateResourceScanner;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Blocks MiniStack deploys that will fail at CloudFormation create time.
 */
public final class MiniStackDeployPreflight {

    private static final String RDS_SUGGESTION =
        "Use Interactive Deployer option 8 (LocalStack) for RDS-backed apps, "
            + "or deploy a MiniStack-friendly app (jenkins, cloudforge-manager, drone, gitea, "
            + "prometheus, grafana, metabase, vault, redis) with authMode: none.";

    private MiniStackDeployPreflight() {
    }

    public static PreflightResult validate(
            DeploymentConfig config,
            ApplicationSpec spec,
            Path canonicalTemplate) throws IOException {
        return validate(
            config,
            spec,
            canonicalTemplate,
            URI.create(MiniStackDeployer.resolveEndpoint()),
            null);
    }

    public static PreflightResult validate(
            DeploymentConfig config,
            ApplicationSpec spec,
            Path canonicalTemplate,
            URI endpoint,
            String stackName) throws IOException {
        List<PreflightViolation> violations = new ArrayList<>();
        validateConfig(config, spec, violations);
        JsonNode template = null;
        if (canonicalTemplate != null) {
            template = TemplateResourceScanner.readTemplate(canonicalTemplate);
            validateTemplate(template, violations);
        }
        String localStackName = stackName == null || stackName.isBlank()
            ? null
            : LocalDeploymentNaming.localStackName(stackName, DeploymentTarget.MINISTACK);
        LocalHostPortConflictChecker.validate(
            DeploymentTarget.MINISTACK,
            endpoint,
            localStackName,
            spec,
            template,
            violations);
        return PreflightResult.of(DeploymentTarget.MINISTACK, violations);
    }

    private static void validateConfig(
            DeploymentConfig config,
            ApplicationSpec spec,
            List<PreflightViolation> violations) {
        if (config == null) {
            return;
        }
        if (config.topology != null
                && config.topology != TopologyType.APPLICATION_SERVICE
                && config.topology != TopologyType.JENKINS_SERVICE) {
            violations.add(new PreflightViolation(
                PreflightSeverity.BLOCK,
                "TOPOLOGY_UNSUPPORTED",
                "Topology " + config.topology + " is not supported on MiniStack MVP.",
                "Use topology APPLICATION_SERVICE or deploy to AWS."));
        }

        boolean needsRds = Boolean.TRUE.equals(config.provisionDatabase)
            || (spec != null && spec.requiresDatabase());
        if (needsRds) {
            violations.add(new PreflightViolation(
                PreflightSeverity.BLOCK,
                "RDS_REQUIRED",
                "Application requires PostgreSQL/MySQL RDS, which MiniStack does not support "
                    + "(AWS::RDS::DBParameterGroup and related types fail at create).",
                RDS_SUGGESTION));
        }
    }

    private static void validateTemplate(JsonNode template, List<PreflightViolation> violations) {
        List<MiniStackCfnResourceCatalog.TemplateResourceRef> unsupported =
            MiniStackCfnResourceCatalog.unsupportedResources(template);
        if (unsupported.isEmpty()) {
            return;
        }
        String resourceList = unsupported.stream()
            .limit(8)
            .map(MiniStackCfnResourceCatalog.TemplateResourceRef::toString)
            .collect(Collectors.joining(", "));
        if (unsupported.size() > 8) {
            resourceList += " (+" + (unsupported.size() - 8) + " more)";
        }
        violations.add(new PreflightViolation(
            PreflightSeverity.BLOCK,
            "UNSUPPORTED_CFN_TYPES",
            "Canonical template contains MiniStack-unsupported resources: " + resourceList,
            RDS_SUGGESTION));
    }
}
