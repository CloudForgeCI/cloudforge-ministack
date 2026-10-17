package com.cloudforgeci.ministack;

import com.cloudforge.core.config.DeploymentConfig;
import com.cloudforge.core.enums.TopologyType;
import com.cloudforge.core.interfaces.ApplicationSpec;
import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalDeploymentNaming;
import com.cloudforge.core.local.LocalEmulatorSpec;
import com.cloudforge.core.local.LocalHostPortConflictChecker;
import com.cloudforge.core.local.MiniStackCfnResourceCatalog;
import com.cloudforge.core.local.PreflightResult;
import com.cloudforge.core.local.PreflightSeverity;
import com.cloudforge.core.local.PreflightViolation;
import com.cloudforge.core.local.TemplateResourceScanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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

    private static final String UNSUPPORTED_SUGGESTION =
        "Use `cloudforge-cli deploy --target localstack` or deploy to AWS, or remove the listed resources "
            + "(RDS clusters such as Aurora, WAF, Config, CloudTrail, Backup and GuardDuty are not available on MiniStack).";

    // The dummy hosted-zone id CloudForgeSynthesizer#seedHostedZoneContext seeds for any deploy
    // using the shared local-emulator account (000000000000, the convention both LocalStack and
    // MiniStack use) -- safe for LocalStack, whose LocalStackTemplateAdapter strips every
    // AWS::Route53::RecordSet before deploy, but MiniStack does no such stripping, so a canonical
    // template referencing this marker would deploy a RecordSet pointed at a hosted zone that
    // doesn't exist. Synthesis can't tell the two targets apart from the account alone; this
    // preflight check catches the result instead.
    private static final String LOCAL_EMULATOR_ZONE_MARKER = "LOCALEMULATORZONE";

    /**
     * The template as text without the Route 53 records that point at the placeholder zone. The
     * adapter removes those records and routes their hostname through the emulator edge, so only a
     * reference to the zone from anywhere else is a problem.
     */
    private static String withoutPlaceholderRecordSets(JsonNode template) {
        JsonNode resources = template.path("Resources");
        if (!resources.isObject()) {
            return template.toString();
        }
        ObjectNode copy = template.deepCopy();
        ObjectNode copyResources = (ObjectNode) copy.get("Resources");
        resources.properties().forEach(entry -> {
            if ("AWS::Route53::RecordSet".equals(entry.getValue().path("Type").asText())
                    && entry.getValue().toString().contains(LOCAL_EMULATOR_ZONE_MARKER)) {
                copyResources.remove(entry.getKey());
            }
        });
        return copy.toString();
    }

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
        if (checksHostPorts(LocalEmulatorSpec.ministack().image(),
                System.getenv(MiniStackTemplateAdapter.ALB_FORWARD_ENV))) {
            LocalHostPortConflictChecker.validate(
                DeploymentTarget.MINISTACK,
                endpoint,
                localStackName,
                spec,
                template,
                violations);
        }
        return PreflightResult.of(DeploymentTarget.MINISTACK, violations);
    }

    /**
     * Two applications collide on a host port only where MiniStack publishes task ports there, which
     * 1.4 did and 1.5 and later do not: they are reached through the load balancer, so any number of
     * stacks can use the same container port.
     */
    static boolean checksHostPorts(String pinnedImage, String forwardOverride) {
        return !MiniStackTemplateAdapter.albForwardsToEcs(pinnedImage, forwardOverride);
    }

    private static void validateConfig(
            DeploymentConfig config,
            ApplicationSpec spec,
            List<PreflightViolation> violations) {
        if (config == null) {
            return;
        }
        // CMS_SERVICE is what every CMS application (WordPress, Drupal, Joomla, Magento and the rest)
        // uses, and all of them deploy on MiniStack.
        if (config.topology != null
                && config.topology != TopologyType.APPLICATION_SERVICE
                && config.topology != TopologyType.JENKINS_SERVICE
                && config.topology != TopologyType.CMS_SERVICE) {
            violations.add(new PreflightViolation(
                PreflightSeverity.BLOCK,
                "TOPOLOGY_UNSUPPORTED",
                "Topology " + config.topology + " is not supported on MiniStack.",
                "Use topology APPLICATION_SERVICE or CMS_SERVICE, or deploy to AWS."));
        }
    }

    private static void validateTemplate(JsonNode template, List<PreflightViolation> violations) {
        List<MiniStackCfnResourceCatalog.TemplateResourceRef> unsupported =
            MiniStackCfnResourceCatalog.unsupportedResources(template);
        if (!unsupported.isEmpty()) {
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
                UNSUPPORTED_SUGGESTION));
        }

        if (withoutPlaceholderRecordSets(template).contains(LOCAL_EMULATOR_ZONE_MARKER)) {
            violations.add(new PreflightViolation(
                PreflightSeverity.BLOCK,
                "UNRESOLVABLE_HOSTED_ZONE",
                "Canonical template references a placeholder Route53 hosted zone "
                    + "(" + LOCAL_EMULATOR_ZONE_MARKER + ") that doesn't exist on MiniStack.",
                "This combination (a custom domain with createZone: false) isn't supported on "
                    + "MiniStack -- either set createZone to true so a real zone is created, or "
                    + "drop the domain and deploy without one."));
        }
    }
}
