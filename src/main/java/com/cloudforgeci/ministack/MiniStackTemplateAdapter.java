package com.cloudforgeci.ministack;

import com.cloudforge.core.local.TemplateAdaptation;
import com.cloudforge.core.local.TemplateAdaptationResult;
import com.cloudforge.core.local.TemplateAdapter;
import com.cloudforge.core.local.TemplateAdapterSupport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Produces the explicitly-audited template deployed to MiniStack.
 *
 * <p>The input is always the canonical AWS template. MiniStack currently does not execute
 * ALB authenticate-cognito/authenticate-oidc actions or EFS CloudFormation resources.
 * EFS-backed task volumes are replaced with Docker host bind mounts so application data
 * persists across local ECS task restarts. Those known divergences and their dependent
 * local wiring are applied to a copy and recorded in the adaptation report; the canonical
 * AWS template is unchanged.</p>
 */
public final class MiniStackTemplateAdapter implements TemplateAdapter {
    public static final MiniStackTemplateAdapter INSTANCE = new MiniStackTemplateAdapter();

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_VOLUME_ROOT = ".ministack-volumes";
    public static final String OUTPUT_LOCAL_URL = "MiniStackLocalUrl";
    public static final String OUTPUT_APPLICATION_URL = "MiniStackApplicationUrl";
    public static final String OUTPUT_AUTHENTICATED_URL = "MiniStackAuthenticatedUrl";
    public static final String OUTPUT_HOST_VOLUME_PREFIX = "MiniStackHostVolume";

    private MiniStackTemplateAdapter() {
    }

    @Override
    public TemplateAdaptationResult adapt(ObjectNode canonicalTemplate, String stackName) {
        return adaptInternal(canonicalTemplate, stackName);
    }

    @Override
    public boolean requiresLocalAuthRuntime(TemplateAdaptationResult result) {
        return result.template().path("Outputs").has(OUTPUT_AUTHENTICATED_URL);
    }

    @Override
    public String applicationUrl(TemplateAdaptationResult result) {
        return result.outputValue(OUTPUT_APPLICATION_URL).orElse(null);
    }

    public static TemplateAdaptationResult adapt(ObjectNode canonicalTemplate) {
        return INSTANCE.adapt(canonicalTemplate, "local");
    }

    private static TemplateAdaptationResult adaptInternal(
            ObjectNode canonicalTemplate,
            String stackName) {
        boolean authenticationEnabled = canonicalTemplate.findValuesAsText("Type").stream()
            .anyMatch(type ->
                "authenticate-oidc".equals(type) || "authenticate-cognito".equals(type));
        ObjectNode local = canonicalTemplate.deepCopy();
        List<TemplateAdaptation> adaptations = new ArrayList<>();
        removeUnsupportedApplicationAutoScaling(local, adaptations);
        replaceUnsupportedEfsWithHostBindMounts(local, adaptations, stackName);
        inlineUnsupportedSecurityGroupIngress(local, adaptations);
        redirectUnsupportedEcsTargets(local, adaptations);
        JsonNode resources = local.path("Resources");

        if (resources.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> fields = resources.properties().iterator();
            while (fields.hasNext()) {
                Map.Entry<String, JsonNode> entry = fields.next();
                ObjectNode resource = asObject(entry.getValue());
                if (resource == null) {
                    continue;
                }
                String type = resource.path("Type").asText();
                ObjectNode properties = asObject(resource.get("Properties"));
                if (properties == null) {
                    continue;
                }

                if ("AWS::ElasticLoadBalancingV2::Listener".equals(type)) {
                    removeUnsupportedAuthActions(
                        properties.get("DefaultActions"),
                        "Resources." + entry.getKey() + ".Properties.DefaultActions",
                        adaptations
                    );
                } else if ("AWS::ElasticLoadBalancingV2::ListenerRule".equals(type)) {
                    removeUnsupportedAuthActions(
                        properties.get("Actions"),
                        "Resources." + entry.getKey() + ".Properties.Actions",
                        adaptations
                    );
                }
            }
        }

        addLocalUrlOutput(local, adaptations, authenticationEnabled);
        return new TemplateAdaptationResult(local, List.copyOf(adaptations));
    }

    private static void removeUnsupportedApplicationAutoScaling(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            String type = entry.getValue().path("Type").asText();
            if ("AWS::ApplicationAutoScaling::ScalableTarget".equals(type)
                    || "AWS::ApplicationAutoScaling::ScalingPolicy".equals(type)) {
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey(),
                    "MiniStack CloudFormation does not support Application Auto Scaling",
                    entry.getValue().deepCopy()
                ));
                remove.add(entry.getKey());
            }
        });
        remove.forEach(resources::remove);
    }

    private static void redirectUnsupportedEcsTargets(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        int applicationPort = findEcsApplicationPort(resources);
        if (applicationPort == 0) {
            return;
        }

        final int localPort = applicationPort;
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null) {
                return;
            }
            String type = resource.path("Type").asText();
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null) {
                return;
            }
            JsonNode actionsNode =
                "AWS::ElasticLoadBalancingV2::Listener".equals(type)
                    ? properties.get("DefaultActions")
                    : "AWS::ElasticLoadBalancingV2::ListenerRule".equals(type)
                        ? properties.get("Actions")
                        : null;
            if (!(actionsNode instanceof ArrayNode actions)) {
                return;
            }

            for (int index = 0; index < actions.size(); index++) {
                JsonNode action = actions.get(index);
                boolean ecsForward = "forward".equals(action.path("Type").asText());
                boolean tlsRedirect = "redirect".equals(action.path("Type").asText())
                    && "HTTPS".equalsIgnoreCase(
                        action.path("RedirectConfig").path("Protocol").asText())
                    && "443".equals(action.path("RedirectConfig").path("Port").asText());
                if (!ecsForward && !tlsRedirect) {
                    continue;
                }
                ObjectNode redirect = MAPPER.createObjectNode();
                redirect.put("Type", "redirect");
                if (action.has("Order")) {
                    redirect.set("Order", action.get("Order"));
                }
                ObjectNode config = redirect.putObject("RedirectConfig");
                config.put("Protocol", "HTTP");
                config.put("Host", "localhost");
                config.put("Port", Integer.toString(localPort));
                config.put("Path", "/#{path}");
                config.put("Query", "#{query}");
                config.put("StatusCode", "HTTP_302");
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties."
                        + ("AWS::ElasticLoadBalancingV2::Listener".equals(type)
                            ? "DefaultActions" : "Actions")
                        + "[" + index + "]",
                    ecsForward
                        ? "MiniStack ALB cannot forward to ECS targets; redirect to local ECS port"
                        : "MiniStack local entry point cannot terminate the canonical HTTPS redirect",
                    action.deepCopy()
                ));
                actions.set(index, redirect);
            }
        });
    }

    private static int findEcsApplicationPort(ObjectNode resources) {
        Iterator<JsonNode> resourceValues = resources.elements();
        while (resourceValues.hasNext()) {
            JsonNode resource = resourceValues.next();
            if ("AWS::ECS::Service".equals(resource.path("Type").asText())) {
                int port = resource.path("Properties")
                    .path("LoadBalancers").path(0).path("ContainerPort").asInt();
                if (port > 0) {
                    return port;
                }
            }
        }
        return 0;
    }

    private static void inlineUnsupportedSecurityGroupIngress(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null
                    || !"AWS::EC2::SecurityGroupIngress".equals(
                        resource.path("Type").asText())) {
                return;
            }

            ObjectNode ingress = asObject(resource.path("Properties").deepCopy());
            JsonNode groupId = ingress == null ? null : ingress.remove("GroupId");
            String targetLogicalId = referencedLogicalId(groupId);
            ObjectNode target =
                targetLogicalId == null ? null : asObject(resources.get(targetLogicalId));
            ObjectNode targetProperties =
                target == null ? null : asObject(target.get("Properties"));
            if (ingress == null || targetProperties == null
                    || !"AWS::EC2::SecurityGroup".equals(target.path("Type").asText())) {
                throw new IllegalArgumentException(
                    "Cannot inline MiniStack security-group ingress " + entry.getKey());
            }

            targetProperties.withArray("SecurityGroupIngress").add(ingress);
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey(),
                "MiniStack CloudFormation requires ingress inline on AWS::EC2::SecurityGroup",
                resource.deepCopy()
            ));
            remove.add(entry.getKey());
        });
        remove.forEach(resources::remove);
    }

    private static String referencedLogicalId(JsonNode reference) {
        if (reference == null) {
            return null;
        }
        if (reference.has("Ref")) {
            return reference.path("Ref").asText();
        }
        JsonNode getAtt = reference.get("Fn::GetAtt");
        return getAtt != null && getAtt.isArray() && !getAtt.isEmpty()
            ? getAtt.get(0).asText()
            : null;
    }

    private static void replaceUnsupportedEfsWithHostBindMounts(
            ObjectNode template,
            List<TemplateAdaptation> adaptations,
            String stackName) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        Set<String> removedLogicalIds = new LinkedHashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = resources.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().path("Type").asText().startsWith("AWS::EFS::")) {
                removedLogicalIds.add(entry.getKey());
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey(),
                    "MiniStack CloudFormation does not support AWS::EFS resources",
                    entry.getValue().deepCopy()
                ));
                fields.remove();
            }
        }

        if (removedLogicalIds.isEmpty()) {
            return;
        }

        ObjectNode outputsNode = asObject(template.get("Outputs"));
        if (outputsNode == null) {
            outputsNode = template.putObject("Outputs");
        }
        final ObjectNode outputs = outputsNode;

        resources.properties().forEach(entry -> {
            ObjectNode resource = asObject(entry.getValue());
            if (resource == null
                    || !"AWS::ECS::TaskDefinition".equals(resource.path("Type").asText())) {
                return;
            }
            ObjectNode properties = asObject(resource.get("Properties"));
            if (properties == null) {
                return;
            }

            JsonNode volumesNode = properties.get("Volumes");
            if (!(volumesNode instanceof ArrayNode volumes)) {
                return;
            }

            for (int index = 0; index < volumes.size(); index++) {
                JsonNode volume = volumes.get(index);
                if (!volume.has("EFSVolumeConfiguration")) {
                    continue;
                }

                String volumeName = volume.path("Name").asText();
                if (volumeName.isBlank()) {
                    throw new IllegalArgumentException(
                        "EFS-backed ECS volume must have a Name for MiniStack host bind mount");
                }

                Path hostPath = resolveHostVolumePath(stackName, volumeName);
                ObjectNode replacement = MAPPER.createObjectNode();
                replacement.put("Name", volumeName);
                String reason;
                boolean hostMountUsable;
                try {
                    Files.createDirectories(hostPath);
                    hostMountUsable = true;
                } catch (IOException e) {
                    // Mirrors LocalStackTemplateAdapter's equivalent block (see its comment).
                    // This path only means anything on the host filesystem; a containerized caller (e.g. Manager's own deploy:create)
                    // has no access to it, so degrade to an ephemeral per-task Docker volume
                    // instead of hard-failing the whole deploy.
                    hostMountUsable = false;
                }
                if (hostMountUsable) {
                    replacement.putObject("Host").put("SourcePath", hostPath.toString());
                    reason = "Replaced unsupported MiniStack EFS with host bind mount at " + hostPath;
                } else {
                    replacement.putObject("DockerVolumeConfiguration")
                        .put("Scope", "task")
                        .put("Autoprovision", false);
                    reason = "Could not create host bind mount directory " + hostPath
                        + " (no real host filesystem access from this deploy caller) — using an "
                        + "ephemeral per-task Docker volume instead. Data in this volume will NOT "
                        + "survive the task being replaced; deploy via the CLI (which runs "
                        + "directly on the host) instead if this application needs its data to persist.";
                }
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.Volumes[" + index + "]",
                    reason,
                    volume.deepCopy()
                ));
                volumes.set(index, replacement);

                if (hostMountUsable) {
                    String outputKey = hostVolumeOutputKey(volumeName);
                    if (!outputs.has(outputKey)) {
                        outputs.putObject(outputKey)
                            .put("Description",
                                "Host bind mount for ECS volume '" + volumeName + "'")
                            .put("Value", hostPath.toString());
                    }
                }
            }
        });

        String adaptedTemplate = template.toString();
        removedLogicalIds.stream()
            .filter(adaptedTemplate::contains)
            .findFirst()
            .ifPresent(logicalId -> {
                throw new IllegalArgumentException(
                    "Unsupported EFS resource " + logicalId
                        + " is still referenced after MiniStack adaptation");
            });
    }

    static Path resolveHostVolumePath(String stackName, String volumeName) {
        Path root = defaultVolumeRoot();
        String safeStackName = stackName == null || stackName.isBlank() ? "local" : stackName;
        String safeVolumeName = volumeName == null || volumeName.isBlank() ? "data" : volumeName;
        return root.resolve(safeStackName).resolve(safeVolumeName).toAbsolutePath().normalize();
    }

    static Path defaultVolumeRoot() {
        String configured = System.getenv("MINISTACK_VOLUME_ROOT");
        if (configured == null || configured.isBlank()) {
            configured = DEFAULT_VOLUME_ROOT;
        }
        Path root = Paths.get(configured);
        if (!root.isAbsolute()) {
            root = Paths.get(System.getProperty("user.dir")).resolve(root);
        }
        return root.toAbsolutePath().normalize();
    }

    private static String hostVolumeOutputKey(String volumeName) {
        return "MiniStackHostVolume"
            + volumeName.substring(0, 1).toUpperCase()
            + volumeName.substring(1);
    }

    private static String stackNameFromTemplatePath(Path canonicalTemplate) {
        String fileName = canonicalTemplate.getFileName().toString();
        if (fileName.endsWith(".template.json")) {
            return fileName.substring(0, fileName.length() - ".template.json".length());
        }
        return fileName;
    }

    public static TemplateAdaptationResult adaptFile(Path canonicalTemplate, Path localTemplate, Path report)
            throws IOException {
        return adaptFile(canonicalTemplate, localTemplate, report,
            stackNameFromTemplatePath(canonicalTemplate));
    }

    public static TemplateAdaptationResult adaptFile(
            Path canonicalTemplate,
            Path localTemplate,
            Path report,
            String stackName) throws IOException {
        return TemplateAdapterSupport.adaptFile(
            INSTANCE, canonicalTemplate, localTemplate, report, stackName);
    }

    private static void removeUnsupportedAuthActions(
            JsonNode actionsNode,
            String path,
            List<TemplateAdaptation> adaptations) {
        if (!(actionsNode instanceof ArrayNode actions)) {
            return;
        }

        for (int index = actions.size() - 1; index >= 0; index--) {
            JsonNode action = actions.get(index);
            String type = action.path("Type").asText();
            if ("authenticate-oidc".equals(type) || "authenticate-cognito".equals(type)) {
                adaptations.add(new TemplateAdaptation(
                    path + "[" + index + "]",
                    "MiniStack ALB supports forward/redirect/fixed-response actions only",
                    action.deepCopy()
                ));
                actions.remove(index);
            }
        }

        if (actions.isEmpty()) {
            throw new IllegalArgumentException(
                "Refusing to create a listener with no action after adapting " + path);
        }
    }

    private static void addLocalUrlOutput(
            ObjectNode template,
            List<TemplateAdaptation> adaptations,
            boolean authenticationEnabled) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        String loadBalancerLogicalId = null;
        Iterator<Map.Entry<String, JsonNode>> fields = resources.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if ("AWS::ElasticLoadBalancingV2::LoadBalancer"
                    .equals(entry.getValue().path("Type").asText())) {
                loadBalancerLogicalId = entry.getKey();
                break;
            }
        }
        if (loadBalancerLogicalId == null) {
            return;
        }

        ObjectNode outputs = asObject(template.get("Outputs"));
        if (outputs == null) {
            outputs = template.putObject("Outputs");
        }
        ObjectNode loadBalancer = asObject(resources.get(loadBalancerLogicalId));
        ObjectNode loadBalancerProperties =
            loadBalancer == null ? null : asObject(loadBalancer.get("Properties"));
        if (loadBalancerProperties == null) {
            return;
        }
        String localName = loadBalancerProperties.path("Name").asText();
        if (localName.isBlank()) {
            localName = "cfc-" + Integer.toUnsignedString(
                loadBalancerLogicalId.hashCode(), 36);
            loadBalancerProperties.put("Name", localName);
            adaptations.add(new TemplateAdaptation(
                "Resources." + loadBalancerLogicalId + ".Properties.Name",
                "Added deterministic MiniStack ALB data-plane name",
                com.fasterxml.jackson.databind.node.NullNode.instance
            ));
        }
        if (!outputs.has("MiniStackLocalUrl")) {
            ObjectNode output = outputs.putObject("MiniStackLocalUrl");
            output.put("Description", "Reachable MiniStack ALB URL");
            output.put("Value", "http://localhost:4566/_alb/" + localName + "/");
        }

        int applicationPort = findEcsApplicationPort(resources);
        if (applicationPort > 0 && !outputs.has("MiniStackApplicationUrl")) {
            outputs.putObject("MiniStackApplicationUrl")
                .put("Description", "Direct local ECS application URL")
                .put("Value", "http://localhost:" + applicationPort);
        }

        if (authenticationEnabled && !outputs.has("MiniStackAuthenticatedUrl")) {
            String authPort =
                System.getenv().getOrDefault("MINISTACK_AUTH_PORT", "4180");
            outputs.putObject("MiniStackAuthenticatedUrl")
                .put("Description", "Deterministic local auth-proxy URL")
                .put("Value",
                    "http://" + localName + ".ministack.localhost:" + authPort);
        }
    }

    private static ObjectNode asObject(JsonNode node) {
        return node instanceof ObjectNode objectNode ? objectNode : null;
    }
}
