package com.cloudforgeci.ministack;

import com.cloudforge.core.local.TemplateAdaptation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;
import java.util.logging.Logger;

/**
 * Lets CloudForge Manager reach its {@code cloudforge-synth-service} sidecar on MiniStack.
 *
 * <p>On Fargate the containers of a task share one network namespace, so Manager calls its sidecar at
 * {@code localhost:8090}. MiniStack runs each container separately, and only the full container name
 * resolves, which contains an id that is not known until the task starts. The adapter points Manager's
 * {@code SYNTH_SERVICE_URL} at an alias that is scoped to the stack and records the sidecar in the
 * template's metadata. After the stack is deployed the deployer attaches that alias to the sidecar's
 * container, in the same way it gives databases a short name.</p>
 */
final class MiniStackSidecarAliases {
    /** Template metadata key holding the sidecars that need an alias. */
    static final String METADATA_KEY = "CloudForgeMiniStackSidecars";
    static final String SYNTH_SIDECAR = "cloudforge-synth-service";
    static final String URL_ENV = "SYNTH_SERVICE_URL";
    private static final int DEFAULT_PORT = 8090;
    private static final int MAX_DNS_LABEL = 63;
    private static final String PREFIX = "synth-";
    private static final String CLUSTER_LABEL = "com.amazonaws.ecs.cluster";
    private static final String CONTAINER_LABEL = "com.amazonaws.ecs.container-name";
    private static final String REVISION_LABEL = "com.amazonaws.ecs.task-definition-version";
    private static final Duration START_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
    private static final Logger LOG = Logger.getLogger(MiniStackSidecarAliases.class.getName());

    private MiniStackSidecarAliases() {
    }

    /**
     * The alias for a stack's sidecar. It is lower case letters, digits and hyphens so it is a valid DNS
     * label, and it carries the stack name so two Managers on one emulator do not share a sidecar.
     */
    static String aliasFor(String stackName) {
        String base = stackName == null || stackName.isBlank() ? "local" : stackName;
        String slug = base.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (slug.isEmpty()) {
            slug = "local";
        }
        String alias = PREFIX + slug;
        return alias.length() <= MAX_DNS_LABEL
            ? alias : alias.substring(0, MAX_DNS_LABEL).replaceAll("-+$", "");
    }

    /**
     * Points each Manager container whose task has the synth sidecar at the stack's alias and records the
     * sidecar in the template metadata for the deployer.
     */
    static void adapt(ObjectNode template, List<TemplateAdaptation> adaptations, String stackName) {
        JsonNode resources = template.path("Resources");
        if (!resources.isObject()) {
            return;
        }
        String alias = aliasFor(stackName);
        boolean[] recorded = {false};
        String[] taskDefinition = {null};
        resources.properties().forEach(entry -> {
            if (!"AWS::ECS::TaskDefinition".equals(entry.getValue().path("Type").asText())) {
                return;
            }
            JsonNode containers = entry.getValue().path("Properties").path("ContainerDefinitions");
            JsonNode sidecar = null;
            for (JsonNode container : containers) {
                if (SYNTH_SIDECAR.equals(container.path("Name").asText())) {
                    sidecar = container;
                }
            }
            if (sidecar == null) {
                return;
            }
            String url = "http://" + alias + ":" + portOf(sidecar);
            for (JsonNode container : containers) {
                if (!(container instanceof ObjectNode node) || node == sidecar
                        || !MiniStackTemplateAdapter.isManagerContainer(node)) {
                    continue;
                }
                ArrayNode environment = node.path("Environment").isArray()
                    ? (ArrayNode) node.get("Environment") : node.putArray("Environment");
                MiniStackTemplateAdapter.upsertEnvironment(environment, URL_ENV, url);
                recorded[0] = true;
                taskDefinition[0] = entry.getKey();
                adaptations.add(new TemplateAdaptation(
                    "Resources." + entry.getKey() + ".Properties.ContainerDefinitions[].Environment",
                    "The synth sidecar runs as a separate container on MiniStack, so Manager reaches it at " + url,
                    NullNode.instance));
            }
        });
        if (recorded[0]) {
            ObjectNode metadata = template.path("Metadata") instanceof ObjectNode existing
                ? existing : template.putObject("Metadata");
            ArrayNode sidecars = metadata.putArray(METADATA_KEY);
            sidecars.addObject().put("container", SYNTH_SIDECAR).put("alias", alias)
                .put("taskDefinition", taskDefinition[0]);
        }
    }

    private static int portOf(JsonNode sidecar) {
        int port = sidecar.path("PortMappings").path(0).path("ContainerPort").asInt(0);
        return port > 0 ? port : DEFAULT_PORT;
    }

    /**
     * Attaches the recorded aliases to the sidecar containers of {@code localStackName}. A container that
     * has not started yet is waited for. Without a Docker daemon this process cannot attach anything, so
     * it warns and carries on: Manager then cannot synthesize until the alias exists.
     */
    static void attach(JsonNode recorded, String localStackName, Function<String, String> revisionOf) {
        if (!recorded.isArray() || recorded.isEmpty()) {
            return;
        }
        if (!DockerDatabaseHost.daemonReachable()) {
            LOG.warning("Docker is not reachable from this process, so the sidecar alias for "
                + localStackName + " was not attached");
            return;
        }
        for (JsonNode item : recorded) {
            String container = item.path("container").asText();
            String alias = item.path("alias").asText();
            String taskDefinition = item.path("taskDefinition").asText(null);
            String revision = taskDefinition == null ? null : revisionOf.apply(taskDefinition);
            if (!attachWhenRunning(container, alias, localStackName, revision)) {
                LOG.warning("Could not attach the alias " + alias + " to " + container + " of "
                    + localStackName + " within " + START_TIMEOUT.toSeconds() + "s");
            }
        }
    }

    private static boolean attachWhenRunning(
            String containerName, String alias, String localStackName, String revision) {
        Instant deadline = Instant.now().plus(START_TIMEOUT);
        while (true) {
            String listing = MiniStackDatabaseProvisioner.run(Duration.ofSeconds(10), "docker", "ps",
                "--filter", "label=" + CONTAINER_LABEL + "=" + containerName,
                "--format", "{{.Names}}\t{{.Label \"" + CLUSTER_LABEL + "\"}}\t{{.Label \"" + REVISION_LABEL + "\"}}");
            String container = selectContainer(listing, localStackName, revision);
            if (container != null) {
                return DockerNetwork.ensureAlias(container, alias) != null;
            }
            if (!Instant.now().isBefore(deadline)) {
                return false;
            }
            MiniStackDatabaseProvisioner.sleep(POLL_INTERVAL);
        }
    }

    /**
     * The first running container, from {@code name<TAB>cluster-arn<TAB>revision} lines, that belongs to
     * the stack and runs {@code revision} of its task definition. MiniStack names a cluster after its
     * stack: {@code cluster/<stack>-<logical id>}. During an update the containers of the previous
     * revision are still running while the new ones start, so the revision is what tells them apart; a
     * null {@code revision} accepts any.
     */
    static String selectContainer(String listing, String localStackName, String revision) {
        if (listing == null || listing.isBlank()) {
            return null;
        }
        String marker = "cluster/" + localStackName + "-";
        for (String line : listing.split("\\R")) {
            String[] fields = line.split("\t", 3);
            if (fields.length < 2 || !fields[1].contains(marker)) {
                continue;
            }
            if (revision == null || (fields.length == 3 && revision.equals(fields[2].trim()))) {
                return fields[0].trim();
            }
        }
        return null;
    }
}
