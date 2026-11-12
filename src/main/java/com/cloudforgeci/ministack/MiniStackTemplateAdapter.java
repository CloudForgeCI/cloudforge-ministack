package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalEmulatorDefaults;
import com.cloudforge.core.local.LocalEmulatorSpec;
import com.cloudforge.core.local.MiniStackCfnResourceCatalog;
import com.cloudforge.core.local.TemplateAdaptation;
import com.cloudforge.core.local.TemplateAdaptationResult;
import com.cloudforge.core.local.TemplateAdapter;
import com.cloudforge.core.local.TemplateAdapterSupport;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /**
     * Creates a host directory for a volume and lets any user write to it. Application images run as an
     * unprivileged user (Jenkins, Grafana), and on a Linux host a bind mount keeps the owner of the
     * directory, so one created by the deploying user would be read-only to the container.
     */
    static void createVolumeDirectory(Path directory) throws IOException {
        Files.createDirectories(directory);
        try {
            Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException e) {
            // A file system without POSIX permissions has no owner to work around.
        }
    }

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
        removeLogRetentionCustomResources(local, adaptations);
        removeComplianceResourcesMiniStackCannotCreate(local, adaptations);
        replacePrivateCertificateAuthorityWithPlainHttp(local, adaptations);
        moveUnsupportedCognitoResourcesToMetadata(local, adaptations);
        markManagerAsLocalEmulatorTarget(local, adaptations);
        substituteMySql57(local, adaptations, hostIsArm64() && !keepMySql57());
        MiniStackSidecarAliases.adapt(local, adaptations, stackName);
        routeSubdomainThroughTheEdge(local, adaptations);
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

    /**
     * Removes the compliance resources MiniStack cannot create (a CloudTrail trail, a GuardDuty detector, a backup
     * selection, Config rules and the web ACL association and logging), so an application with a production security
     * profile deploys without those controls instead of being refused. Nothing in the template may still refer to a
     * removed resource: the deploy fails with the names if something does, rather than leaving a dangling reference.
     */
    /** Set to {@code true} to keep MySQL 5.7 on an arm64 host, for example with the amd64 image pulled by hand. */
    static final String KEEP_MYSQL57_ENV = "CFC_MINISTACK_KEEP_MYSQL57";

    private static boolean hostIsArm64() {
        String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        return "aarch64".equals(arch) || "arm64".equals(arch);
    }

    private static boolean keepMySql57() {
        return Boolean.parseBoolean(System.getenv(KEEP_MYSQL57_ENV));
    }

    /**
     * The official {@code mysql:5.7} image is built for amd64 only, so an arm64 host cannot pull it and the
     * database never starts. There the instance runs MySQL 8.0 instead, and a parameter group of the 5.7
     * family follows it. Only the local run changes; the template for AWS keeps 5.7.
     */
    static void substituteMySql57(ObjectNode template, List<TemplateAdaptation> adaptations, boolean substitute) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (!substitute || resources == null) {
            return;
        }
        resources.properties().forEach(entry -> {
            ObjectNode properties = asObject(entry.getValue().path("Properties"));
            if (properties == null) {
                return;
            }
            String type = entry.getValue().path("Type").asText();
            if ("AWS::RDS::DBInstance".equals(type) && "mysql".equals(properties.path("Engine").asText())
                    && properties.path("EngineVersion").asText("").startsWith("5.7")) {
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.EngineVersion",
                    "mysql:5.7 has no arm64 image, so this host runs MySQL 8.0 instead",
                    properties.get("EngineVersion").deepCopy()));
                properties.put("EngineVersion", "8.0");
            } else if ("AWS::RDS::DBParameterGroup".equals(type)
                    && "mysql5.7".equals(properties.path("Family").asText())) {
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.Family",
                    "The database runs MySQL 8.0 here, so its parameter group uses that family",
                    properties.get("Family").deepCopy()));
                properties.put("Family", "mysql8.0");
            }
        });
    }

    /**
     * A custom resource that only reads: a STAGING or PRODUCTION deployment uses these to check that a
     * service such as Security Hub or Inspector is enabled. MiniStack does not run the Lambda behind an
     * {@code AwsCustomResource}, so the call never completes and the stack waits on it for good. It changes
     * nothing, so removing it loses only the check.
     */
    static boolean isReadOnlyVerification(JsonNode resource) {
        return "Custom::AWS".equals(resource.path("Type").asText())
            && READ_ONLY_ACTION.matcher(resource.path("Properties").path("Create").toString()).find();
    }

    private static final java.util.regex.Pattern READ_ONLY_ACTION =
        java.util.regex.Pattern.compile("\\\\\"action\\\\\":\\\\\"(get|batchGet|describe|list)", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static void removeComplianceResourcesMiniStackCannotCreate(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            if (MiniStackCfnResourceCatalog.REMOVED_COMPLIANCE_TYPES.contains(entry.getValue().path("Type").asText())
                    || isReadOnlyVerification(entry.getValue())) {
                remove.add(entry.getKey());
            }
        });
        if (remove.isEmpty()) {
            return;
        }
        for (String key : remove) {
            adaptations.add(new TemplateAdaptation(
                "Resources." + key,
                "MiniStack cannot create " + resources.get(key).path("Type").asText()
                    + "; the control is not applied locally",
                resources.get(key).deepCopy()));
            resources.remove(key);
        }
        // A custom resource that only records an attribute of a removed resource, such as the trail's ARN in
        // SSM, has nothing left to read, and MiniStack does not run the Lambda behind it anyway.
        boolean cascaded = true;
        while (cascaded) {
            cascaded = false;
            for (var entry : List.copyOf(resources.properties())) {
                String type = entry.getValue().path("Type").asText();
                if (("AWS::CloudFormation::CustomResource".equals(type) || type.startsWith("Custom::"))
                        && referencesAny(entry.getValue(), remove)) {
                    adaptations.add(new TemplateAdaptation(
                        "Resources." + entry.getKey(),
                        "It reads a resource MiniStack cannot create, so it is removed with it",
                        entry.getValue().deepCopy()));
                    resources.remove(entry.getKey());
                    remove.add(entry.getKey());
                    cascaded = true;
                }
            }
        }
        dropDanglingDependsOn(resources, remove);
        List<String> referencedBy = new ArrayList<>();
        resources.properties().forEach(entry -> {
            if (referencesAny(entry.getValue(), remove)) {
                referencedBy.add("Resources." + entry.getKey() + " (" + entry.getValue().path("Type").asText() + ")");
            }
        });
        template.path("Outputs").properties().forEach(entry -> {
            if (referencesAny(entry.getValue(), remove)) {
                referencedBy.add("Outputs." + entry.getKey());
            }
        });
        if (!referencedBy.isEmpty()) {
            throw new IllegalArgumentException("Cannot remove " + remove + " for MiniStack: still referred to by "
                + referencedBy);
        }
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

    /**
     * CDK sets log-group retention for some constructs, such as the RDS log exports, through a
     * {@code Custom::LogRetention} resource that a CDK-provided Lambda function has to answer.
     * MiniStack does not run that function, so the resource stays in {@code CREATE_IN_PROGRESS}
     * and the stack never finishes. Retention is a CloudWatch Logs setting with no bearing on the
     * running application, so the custom resources are removed together with the provider function,
     * role and policy once nothing else refers to them.
     */
    private static void removeLogRetentionCustomResources(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            if ("Custom::LogRetention".equals(entry.getValue().path("Type").asText())) {
                remove.add(entry.getKey());
            }
        });
        if (remove.isEmpty()) {
            return;
        }

        // The singleton provider CDK creates for these resources, named LogRetention<hash>, with its
        // service role and default policy. Only taken along when no other resource refers to them.
        List<String> provider = new ArrayList<>();
        resources.properties().forEach(entry -> {
            String type = entry.getValue().path("Type").asText();
            if (entry.getKey().startsWith("LogRetention") && !remove.contains(entry.getKey())
                    && ("AWS::Lambda::Function".equals(type) || "AWS::IAM::Role".equals(type)
                        || "AWS::IAM::Policy".equals(type))) {
                provider.add(entry.getKey());
            }
        });
        List<String> takenAlong = new ArrayList<>();
        for (String key : provider) {
            boolean referencedElsewhere = resources.properties().stream()
                .filter(entry -> !remove.contains(entry.getKey()) && !provider.contains(entry.getKey()))
                .anyMatch(entry -> entry.getValue().toString().contains("\"" + key + "\""))
                || template.path("Outputs").toString().contains("\"" + key + "\"");
            if (!referencedElsewhere) {
                takenAlong.add(key);
            }
        }

        for (String key : remove) {
            adaptations.add(new TemplateAdaptation(
                "Resources." + key,
                "MiniStack does not run the Lambda behind Custom::LogRetention; log retention is not applied locally",
                resources.get(key).deepCopy()));
        }
        for (String key : takenAlong) {
            adaptations.add(new TemplateAdaptation(
                "Resources." + key,
                "Log retention provider is unused once its custom resources are removed",
                resources.get(key).deepCopy()));
        }
        remove.forEach(resources::remove);
        takenAlong.forEach(resources::remove);
    }

    /**
     * Without a domain, CloudForge issues the load balancer's certificate from an AWS Private CA
     * (a CA, a root certificate, its activation and an ACM permission, then an ACM certificate that
     * names the CA). MiniStack's CloudFormation has no {@code AWS::ACMPCA} types, and its load
     * balancer cannot terminate HTTPS anyway. The chain and the certificates issued from it are
     * removed, the HTTPS listener goes with them, and the HTTP listener, which redirected to HTTPS,
     * forwards to the application's target group instead.
     */
    private static void replacePrivateCertificateAuthorityWithPlainHttp(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }

        List<String> privateCa = new ArrayList<>();
        resources.properties().forEach(entry -> {
            JsonNode resource = entry.getValue();
            String type = resource.path("Type").asText();
            boolean issuedFromPrivateCa = "AWS::CertificateManager::Certificate".equals(type)
                && resource.path("Properties").has("CertificateAuthorityArn");
            if (type.startsWith("AWS::ACMPCA::") || issuedFromPrivateCa) {
                privateCa.add(entry.getKey());
            }
        });
        if (privateCa.isEmpty()) {
            return;
        }

        // Listeners whose certificates all come from the removed chain are HTTPS-only.
        List<String> httpsListeners = new ArrayList<>();
        String forwardTarget = null;
        for (var entry : (Iterable<Map.Entry<String, JsonNode>>) resources.properties()) {
            JsonNode resource = entry.getValue();
            if (!"AWS::ElasticLoadBalancingV2::Listener".equals(resource.path("Type").asText())) {
                continue;
            }
            JsonNode properties = resource.path("Properties");
            if ("HTTPS".equals(properties.path("Protocol").asText())
                    && referencesAny(properties.path("Certificates"), privateCa)) {
                httpsListeners.add(entry.getKey());
                for (JsonNode action : properties.path("DefaultActions")) {
                    if ("forward".equals(action.path("Type").asText()) && forwardTarget == null
                            && action.has("TargetGroupArn")) {
                        forwardTarget = action.path("TargetGroupArn").toString();
                    }
                }
            }
        }

        if (forwardTarget != null) {
            for (var entry : (Iterable<Map.Entry<String, JsonNode>>) resources.properties()) {
                JsonNode resource = entry.getValue();
                if (!"AWS::ElasticLoadBalancingV2::Listener".equals(resource.path("Type").asText())
                        || httpsListeners.contains(entry.getKey())) {
                    continue;
                }
                ObjectNode properties = asObject(resource.get("Properties"));
                if (properties == null || !properties.path("DefaultActions").isArray()) {
                    continue;
                }
                ArrayNode actions = (ArrayNode) properties.get("DefaultActions");
                for (int i = 0; i < actions.size(); i++) {
                    JsonNode action = actions.get(i);
                    if ("redirect".equals(action.path("Type").asText())
                            && "HTTPS".equals(action.path("RedirectConfig").path("Protocol").asText())) {
                        try {
                            ObjectNode forward = (ObjectNode) new ObjectMapper()
                                .readTree("{\"Type\":\"forward\",\"TargetGroupArn\":" + forwardTarget + "}");
                            actions.set(i, forward);
                            adaptations.add(new TemplateAdaptation(
                                "Resources." + entry.getKey() + ".Properties.DefaultActions",
                                "MiniStack cannot terminate HTTPS; forward HTTP to the application instead of redirecting",
                                action.deepCopy()));
                        } catch (JsonProcessingException e) {
                            throw new IllegalStateException(e);
                        }
                    }
                }
            }
        }

        List<String> remove = new ArrayList<>(privateCa);
        remove.addAll(httpsListeners);
        for (String key : remove) {
            adaptations.add(new TemplateAdaptation(
                "Resources." + key,
                "MiniStack has no AWS Private CA and cannot terminate HTTPS; the private certificate chain is not created locally",
                resources.get(key).deepCopy()));
        }
        remove.forEach(resources::remove);
        dropDanglingDependsOn(resources, remove);
    }

    private static boolean referencesAny(JsonNode node, List<String> logicalIds) {
        String text = node.toString();
        return logicalIds.stream().anyMatch(id -> text.contains("\"" + id + "\""));
    }

    private static void dropDanglingDependsOn(ObjectNode resources, List<String> removed) {
        resources.properties().forEach(entry -> {
            if (!(entry.getValue() instanceof ObjectNode resource) || !resource.has("DependsOn")) {
                return;
            }
            JsonNode dependsOn = resource.get("DependsOn");
            if (dependsOn.isArray()) {
                ArrayNode kept = resource.arrayNode();
                dependsOn.forEach(item -> {
                    if (!removed.contains(item.asText())) {
                        kept.add(item);
                    }
                });
                if (kept.isEmpty()) {
                    resource.remove("DependsOn");
                } else {
                    resource.set("DependsOn", kept);
                }
            } else if (removed.contains(dependsOn.asText())) {
                resource.remove("DependsOn");
            }
        });
    }

    /**
     * MiniStack's CloudFormation creates user pools, groups, clients and domains, but has no
     * {@code AWS::Cognito::UserPoolUser}, {@code UserPoolUserToGroupAttachment} or
     * {@code ManagedLoginBranding}. Branding only styles the hosted sign-in page and is dropped. The
     * users and their group memberships are removed from the resources and recorded under
     * {@link MiniStackCognitoUsers#METADATA_KEY}, so the deployer can create them through the
     * Cognito API once the pool exists.
     */
    private static void moveUnsupportedCognitoResourcesToMetadata(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        ObjectNode recorded = MiniStackCognitoUsers.record(resources);
        List<String> remove = new ArrayList<>();
        resources.properties().forEach(entry -> {
            String type = entry.getValue().path("Type").asText();
            if ("AWS::Cognito::ManagedLoginBranding".equals(type)
                    || "AWS::Cognito::UserPoolUser".equals(type)
                    || "AWS::Cognito::UserPoolUserToGroupAttachment".equals(type)) {
                remove.add(entry.getKey());
            }
        });
        if (remove.isEmpty()) {
            return;
        }
        for (String key : remove) {
            String type = resources.get(key).path("Type").asText();
            adaptations.add(new TemplateAdaptation(
                "Resources." + key,
                "ManagedLoginBranding".equals(type.substring(type.lastIndexOf(':') + 1))
                    ? "MiniStack has no managed login branding; the hosted sign-in page is not styled locally"
                    : "MiniStack CloudFormation cannot create Cognito users or group memberships; they are created through the Cognito API after the stack",
                resources.get(key).deepCopy()));
        }
        remove.forEach(resources::remove);
        dropDanglingDependsOn(resources, remove);
        if (recorded.path("users").size() > 0 || recorded.path("memberships").size() > 0) {
            ObjectNode metadata = template.has("Metadata") && template.get("Metadata").isObject()
                ? (ObjectNode) template.get("Metadata")
                : template.putObject("Metadata");
            metadata.set(MiniStackCognitoUsers.METADATA_KEY, recorded);
        }
    }

    /** Set to {@code true} or {@code false} to override the choice made from the pinned MiniStack image. */
    static final String ALB_FORWARD_ENV = "CFC_MINISTACK_ALB_FORWARD";

    /**
     * MiniStack 1.5 and later forward a load balancer listener to ECS targets, and do not publish task
     * ports on the host, so the listener has to keep its forward action. 1.4.x could not forward, and
     * published the task port, which the redirect below points at. The pinned image decides, and
     * {@value #ALB_FORWARD_ENV} overrides it for an emulator started by hand.
     */
    static boolean albForwardsToEcs(String pinnedImage, String override) {
        if (override != null && !override.isBlank()) {
            return Boolean.parseBoolean(override.trim());
        }
        return isVersion15OrLater(pinnedImage);
    }

    /** Whether an image tag such as {@code ministackorg/ministack:1.5.27} names MiniStack 1.5 or later. */
    static boolean isVersion15OrLater(String image) {
        Matcher version =
            Pattern.compile(":(\\d+)\\.(\\d+)").matcher(image == null ? "" : image);
        return version.find()
            && (Integer.parseInt(version.group(1)) > 1
                || (Integer.parseInt(version.group(1)) == 1 && Integer.parseInt(version.group(2)) >= 5));
    }

    /** Placeholder hosted zone id CloudForgeSynthesizer seeds for the shared local emulator account. */
    static final String LOCAL_EMULATOR_ZONE_MARKER = "LOCALEMULATORZONE";

    /** Environment variable the emulator edge reads to give a task its own hostname. */
    static final String EDGE_HOSTNAME_ENV = "CFC_LOCALSTACK_EDGE_HOSTNAME";

    /**
     * A deployment with a {@code subdomain} creates a Route 53 record such as {@code
     * jenkins2.example.com.}. The records that point at the placeholder hosted zone cannot be created
     * on MiniStack and are removed. The record's first label gives the task its own edge hostname,
     * {@code jenkins2.cloudforge.localhost}, passed to every container of the stack's tasks in {@code
     * CFC_LOCALSTACK_EDGE_HOSTNAME}, so two stacks of one application are served on separate hosts
     * instead of sharing the application's default one.
     */
    private static void routeSubdomainThroughTheEdge(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        List<String> remove = new ArrayList<>();
        String recordName = null;
        for (Map.Entry<String, JsonNode> entry : resources.properties()) {
            if (!"AWS::Route53::RecordSet".equals(entry.getValue().path("Type").asText())) {
                continue;
            }
            JsonNode name = entry.getValue().path("Properties").path("Name");
            if (recordName == null && name.isTextual() && !name.asText().isBlank()) {
                recordName = name.asText();
            }
            if (entry.getValue().toString().contains(LOCAL_EMULATOR_ZONE_MARKER)) {
                remove.add(entry.getKey());
            }
        }
        String edgeHostname = edgeHostname(recordName);
        for (String key : remove) {
            adaptations.add(new TemplateAdaptation(
                "Resources." + key,
                "The record points at a hosted zone that does not exist on MiniStack; the emulator edge routes the hostname",
                resources.get(key).deepCopy()));
            resources.remove(key);
        }
        if (!remove.isEmpty()) {
            dropDanglingDependsOn(resources, remove);
        }
        if (edgeHostname == null) {
            return;
        }
        resources.properties().forEach(entry -> {
            if (!"AWS::ECS::TaskDefinition".equals(entry.getValue().path("Type").asText())) {
                return;
            }
            JsonNode containers = entry.getValue().path("Properties").path("ContainerDefinitions");
            for (JsonNode container : containers) {
                if (!(container instanceof ObjectNode containerNode)) {
                    continue;
                }
                ArrayNode environment = container.path("Environment").isArray()
                    ? (ArrayNode) container.path("Environment")
                    : containerNode.putArray("Environment");
                upsertEnvironment(environment, EDGE_HOSTNAME_ENV, edgeHostname);
            }
            adaptations.add(new TemplateAdaptation(
                "Resources." + entry.getKey() + ".Properties.ContainerDefinitions[*].Environment",
                "Gives the task its own edge hostname, " + edgeHostname,
                null));
        });
    }

    /**
     * {@code jenkins2.example.com.} becomes {@code jenkins2.cloudforge.localhost}: only the first label
     * is kept, since the registered domain means nothing under the emulator's own suffix. Null when
     * there is no usable record name.
     */
    static String edgeHostname(String route53RecordName) {
        if (route53RecordName == null) {
            return null;
        }
        String trimmed = route53RecordName.trim();
        while (trimmed.endsWith(".")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int dot = trimmed.indexOf('.');
        String label = (dot < 0 ? trimmed : trimmed.substring(0, dot)).toLowerCase(Locale.ROOT).trim();
        return label.isBlank() ? null : label + ".cloudforge.localhost";
    }

    /**
     * CloudForge Manager decides where first-time setup is accepted from by its target. Reached
     * through the emulator's load balancer, a request is not from loopback, so a Manager that does not
     * know it runs in a local emulator refuses setup from a browser. Tells it, and where the emulator is.
     */
    private static void markManagerAsLocalEmulatorTarget(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null) {
            return;
        }
        resources.properties().forEach(entry -> {
            if (!"AWS::ECS::TaskDefinition".equals(entry.getValue().path("Type").asText())) {
                return;
            }
            JsonNode containers = entry.getValue().path("Properties").path("ContainerDefinitions");
            for (JsonNode container : containers) {
                if (!(container instanceof ObjectNode containerNode) || !isManagerContainer(containerNode)) {
                    continue;
                }
                ArrayNode environment = containerNode.path("Environment").isArray()
                    ? (ArrayNode) containerNode.get("Environment")
                    : containerNode.putArray("Environment");
                upsertEnvironment(environment, "CFC_MANAGER_TARGET", "ministack");
                upsertEnvironment(environment, "CFC_MANAGER_MINISTACK_ENDPOINT",
                    "http://" + LocalEmulatorDefaults.MINISTACK_CONTAINER + ":4566");
                upsertEnvironment(environment, "CFC_MANAGER_MINISTACK_AGENT_URL", AGENT_URL_FOR_CONTAINERS);
                MiniStackAgent.existingToken(System.getenv("CFC_AGENT_TOKEN"), MiniStackAgent.defaultHome(System.getenv()))
                    .ifPresent(agentToken -> upsertEnvironment(
                        environment, "CFC_MANAGER_MINISTACK_AGENT_TOKEN", agentToken));
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.ContainerDefinitions[].Environment",
                    "Manager runs in a local emulator: setup is accepted through the load balancer, and the emulator endpoint is set",
                    NullNode.instance));
            }
        });
    }

    /** Where a Manager container reaches the agent, which runs on the Docker host. */
    static final String AGENT_URL_FOR_CONTAINERS = "http://host.docker.internal:4570";

    static boolean isManagerContainer(ObjectNode container) {
        for (JsonNode env : container.path("Environment")) {
            String name = env.path("Name").asText();
            if ("CFC_MANAGER_PORT".equals(name) || "CFC_MANAGER_AUTH_MODE".equals(name)) {
                return true;
            }
        }
        return container.path("Image").asText("").toLowerCase(Locale.ROOT).contains("cloudforge-manager");
    }

    static void upsertEnvironment(ArrayNode environment, String name, String value) {
        for (JsonNode entry : environment) {
            if (name.equals(entry.path("Name").asText()) && entry instanceof ObjectNode existing) {
                existing.put("Value", value);
                return;
            }
        }
        environment.addObject().put("Name", name).put("Value", value);
    }

    private static void redirectUnsupportedEcsTargets(
            ObjectNode template,
            List<TemplateAdaptation> adaptations) {
        ObjectNode resources = asObject(template.get("Resources"));
        if (resources == null || albForwardsToEcs(
                LocalEmulatorSpec.ministack().image(), System.getenv(ALB_FORWARD_ENV))) {
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
        Set<String> retainedFileSystems = new LinkedHashSet<>();
        Iterator<Map.Entry<String, JsonNode>> fields = resources.properties().iterator();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> entry = fields.next();
            if (entry.getValue().path("Type").asText().startsWith("AWS::EFS::")) {
                removedLogicalIds.add(entry.getKey());
                if (entry.getValue().path("DeletionPolicy").asText("").startsWith("Retain")) {
                    retainedFileSystems.add(entry.getKey());
                }
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
                    if (runsInContainer() && System.getenv("MINISTACK_VOLUME_ROOT") == null) {
                        throw new IOException("running inside a container, whose file system is not the Docker host's");
                    }
                    createVolumeDirectory(hostPath);
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
                    // A file system the template retains outlives the stack, so its directory is not
                    // listed under the key the deployer removes on destroy.
                    boolean retained = retainedFileSystems.contains(
                        volume.path("EFSVolumeConfiguration").path("FilesystemId").path("Ref").asText(""));
                    String outputKey = hostVolumeOutputKey(volumeName, retained);
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

    /**
     * Whether this process runs inside a container. Directories it creates then exist in the container, not on
     * the Docker host that resolves a bind mount, unless {@code MINISTACK_VOLUME_ROOT} names a path both share.
     */
    static boolean runsInContainer() {
        return Files.exists(Paths.get("/.dockerenv"));
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

    private static String hostVolumeOutputKey(String volumeName, boolean retained) {
        return (retained ? "MiniStackRetainedHostVolume" : MiniStackDeployer.HOST_VOLUME_OUTPUT_PREFIX)
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
        boolean taskPortPublished = !albForwardsToEcs(
            LocalEmulatorSpec.ministack().image(), System.getenv(ALB_FORWARD_ENV));
        if (applicationPort > 0 && taskPortPublished && !outputs.has("MiniStackApplicationUrl")) {
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
