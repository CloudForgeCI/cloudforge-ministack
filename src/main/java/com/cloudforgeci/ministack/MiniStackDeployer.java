package com.cloudforgeci.ministack;

import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalDeployResult;
import com.cloudforge.core.local.LocalDeployer;
import com.cloudforge.core.local.LocalDeploymentCatalog;
import com.cloudforge.core.local.LocalResourceChange;
import com.cloudforge.core.local.LocalSameApplicationStackReplacer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.Capability;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetStatus;
import software.amazon.awssdk.services.cloudformation.model.ChangeSetType;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.CreateChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.DeleteStackRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStackEventsRequest;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.ExecuteChangeSetRequest;
import software.amazon.awssdk.services.cloudformation.model.GetTemplateRequest;
import software.amazon.awssdk.services.cloudformation.model.ListStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Output;
import software.amazon.awssdk.services.cloudformation.model.ResourceChange;
import software.amazon.awssdk.services.cloudformation.model.StackSummary;
import software.amazon.awssdk.services.cloudformation.model.Tag;
import software.amazon.awssdk.services.ssm.SsmClient;
import software.amazon.awssdk.services.sts.StsClient;
import software.amazon.awssdk.services.ssm.model.ParameterNotFoundException;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Creates and incrementally updates CloudFormation stacks against MiniStack.
 * All calls use the configured local endpoint; no host AWS CLI or AWS account is used.
 */
public final class MiniStackDeployer implements LocalDeployer {
    /** Disable same-app stack replacement when set to {@code false} or {@code 0}. */
    public static final String REPLACE_SAME_APP_ENV = "CFC_MINISTACK_REPLACE_SAME_APP";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Outputs the adapter writes for the host directories that replace EFS file systems that are deleted with the stack. */
    static final String HOST_VOLUME_OUTPUT_PREFIX = "MiniStackHostVolume";
    private static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final CloudFormationClient cloudFormation;
    private final SsmClient ssm;
    private final String endpoint;
    private final String region;
    private final AwsCredentialsProvider credentials;

    public MiniStackDeployer() {
        this(
            resolveEndpoint(),
            System.getenv().getOrDefault("AWS_DEFAULT_REGION", "us-east-1")
        );
    }

    /**
     * MiniStack gateway URL from {@code AWS_ENDPOINT_URL} (AWS CLI convention),
     * defaulting to {@code http://localhost:4566}.
     */
    public static String resolveEndpoint() {
        String awsEndpoint = System.getenv("AWS_ENDPOINT_URL");
        if (awsEndpoint != null && !awsEndpoint.isBlank()) {
            return awsEndpoint.trim();
        }
        return "http://localhost:4566";
    }

    /** Visible for tests and custom endpoint wiring. */
    public MiniStackDeployer(String endpoint, String region) {
        this(endpoint, region, null);
    }

    /**
     * Signs requests with {@code credentials}, for example the credentials of an assumed role in
     * another account. Without them the account comes from {@code AWS_ACCESS_KEY_ID}.
     */
    public MiniStackDeployer(String endpoint, String region, AwsCredentialsProvider credentials) {
        this.endpoint = endpoint;
        this.region = region;
        this.credentials = credentials != null
            ? credentials : StaticCredentialsProvider.create(MiniStackCredentials.fromEnvironment());
        cloudFormation = CloudFormationClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(this.credentials)
            .build();
        ssm = SsmClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(this.credentials)
            .build();
    }

    /** CloudFormation rejects an inline template body over this many bytes; larger ones need S3. */
    static final int MAX_INLINE_TEMPLATE_BYTES = 51_200;

    /**
     * The template without the whitespace CDK pretty-prints it with, which is more than half its size.
     * Fails with an explanation when it is still too large to send inline.
     */
    static String compact(String templateBody) throws IOException {
        String compact = MAPPER.writeValueAsString(MAPPER.readTree(templateBody));
        int bytes = compact.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_INLINE_TEMPLATE_BYTES) {
            throw new IOException("The template is " + bytes + " bytes, over the " + MAX_INLINE_TEMPLATE_BYTES
                + "-byte limit CloudFormation accepts inline. Real AWS needs it uploaded to S3 as well.");
        }
        return compact;
    }

    /**
     * Creates the databases the template asks for and returns the template rewritten to use them,
     * with generated secrets given local values.
     */
    private String provisionDatabases(String templateBody, List<String> createdDatabases) throws IOException {
        var template = (ObjectNode) MAPPER.readTree(templateBody);
        var plans = MiniStackDatabases.plan(template);
        if (plans.isEmpty()) {
            return MAPPER.writeValueAsString(MiniStackDatabases.inlineGeneratedSecrets(template));
        }
        try (MiniStackDatabaseProvisioner provisioner = new MiniStackDatabaseProvisioner(endpoint, region, credentials)) {
            var endpoints = provisioner.provision(plans);
            createdDatabases.addAll(provisioner.createdIdentifiers());
            return MAPPER.writeValueAsString(MiniStackDatabases.inlineGeneratedSecrets(
                MiniStackDatabases.rewrite(template, plans, endpoints)));
        }
    }

    /** The account the credentials of this deployer belong to. */
    private String accountId() {
        try (StsClient sts = StsClient.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .build()) {
            return sts.getCallerIdentity().account();
        }
    }

    /**
     * Gives the containers of a stack in an account other than the default that account's access key. MiniStack
     * serves a task's role credentials without knowing which account the task belongs to and always answers as
     * the default account, so an application that asks AWS who it is, such as a Manager, would otherwise be
     * told the wrong account. A container that sets the variables itself keeps its own values.
     */
    static String pinTasksToAccount(String templateBody, String accountId) throws IOException {
        if (accountId == null || MiniStackCredentials.DEFAULT_ACCOUNT.equals(accountId)) {
            return templateBody;
        }
        var template = (ObjectNode) MAPPER.readTree(templateBody);
        template.path("Resources").properties().forEach(resource -> {
            if (!"AWS::ECS::TaskDefinition".equals(resource.getValue().path("Type").asText())) {
                return;
            }
            for (JsonNode definition : resource.getValue().path("Properties").path("ContainerDefinitions")) {
                var container = (ObjectNode) definition;
                var environment = container.has("Environment") && container.get("Environment").isArray()
                    ? (ArrayNode) container.get("Environment")
                    : container.putArray("Environment");
                putIfAbsent(environment, "AWS_ACCESS_KEY_ID", accountId);
                putIfAbsent(environment, "AWS_SECRET_ACCESS_KEY", "test");
            }
        });
        return MAPPER.writeValueAsString(template);
    }

    private static void putIfAbsent(ArrayNode environment, String name, String value) {
        for (JsonNode entry : environment) {
            if (name.equals(entry.path("Name").asText())) {
                return;
            }
        }
        environment.addObject().put("Name", name).put("Value", value);
    }

    private void requireCdkBootstrap(String templateBody) throws IOException {
        var parameter = MiniStackCdkBootstrap.requiredParameter(templateBody);
        if (parameter.isEmpty()) {
            return;
        }
        try {
            ssm.getParameter(r -> r.name(parameter.get()));
        } catch (ParameterNotFoundException e) {
            throw new IOException(MiniStackCdkBootstrap.instructions(endpoint, region, parameter.get()), e);
        }
    }

    @Override
    public LocalDeployResult deploy(String stackName, Path template) throws IOException {
        boolean exists = stackExists(stackName);
        String templateBody = Files.readString(template);
        requireCdkBootstrap(templateBody);
        if (!exists) {
            emptyHostVolumes(hostVolumesIn(templateBody), MiniStackTemplateAdapter.defaultVolumeRoot());
        }
        List<String> createdDatabases = new ArrayList<>();
        templateBody = compact(pinTasksToAccount(provisionDatabases(templateBody, createdDatabases), accountId()));
        try {
            return deployStack(stackName, templateBody, exists);
        } catch (IOException | RuntimeException e) {
            // A first deploy that fails leaves no stack, so a later destroy cannot find the databases it
            // created. Remove them here.
            if (!exists && !createdDatabases.isEmpty()) {
                try (MiniStackDatabaseProvisioner provisioner = new MiniStackDatabaseProvisioner(endpoint, region, credentials)) {
                    provisioner.delete(createdDatabases);
                }
            }
            throw e;
        }
    }

    private LocalDeployResult deployStack(String stackName, String templateBody, boolean exists) throws IOException {
        if (exists && templateMatches(stackName, templateBody)) {
            return new LocalDeployResult(stackName, false, true, List.of(), outputs(stackName));
        }
        String changeSetName = "cfc-" + UUID.randomUUID().toString().substring(0, 8);

        cloudFormation.createChangeSet(CreateChangeSetRequest.builder()
            .stackName(stackName)
            .changeSetName(changeSetName)
            .changeSetType(exists ? ChangeSetType.UPDATE : ChangeSetType.CREATE)
            .templateBody(templateBody)
            .capabilities(Capability.CAPABILITY_IAM, Capability.CAPABILITY_NAMED_IAM)
            .build());

        var changeSet = waitForChangeSet(stackName, changeSetName);
        if (changeSet.status() == ChangeSetStatus.FAILED) {
            String reason = changeSet.statusReason() == null ? "" : changeSet.statusReason();
            deleteChangeSet(stackName, changeSetName);
            if (isNoOp(reason)) {
                return new LocalDeployResult(stackName, false, true, List.of(), outputs(stackName));
            }
            throw new IOException("MiniStack change set failed: " + reason);
        }

        List<LocalResourceChange> changes = changeSet.changes().stream()
            .map(change -> toChange(change.resourceChange()))
            .toList();
        cloudFormation.executeChangeSet(ExecuteChangeSetRequest.builder()
            .stackName(stackName)
            .changeSetName(changeSetName)
            .build());

        var request = DescribeStacksRequest.builder().stackName(stackName).build();
        try {
            if (exists) {
                cloudFormation.waiter().waitUntilStackUpdateComplete(request);
            } else {
                cloudFormation.waiter().waitUntilStackCreateComplete(request);
            }
        } catch (Exception e) {
            throw new IOException(
                "MiniStack deployment failed for " + stackName + ":\n" + recentEvents(stackName),
                e
            );
        }

        createCognitoUsers(stackName, templateBody);
        return new LocalDeployResult(stackName, !exists, false, changes, outputs(stackName));
    }

    /** Creates the Cognito users the template declared, which MiniStack's CloudFormation cannot. */
    private void createCognitoUsers(String stackName, String templateBody) throws IOException {
        JsonNode recorded = MAPPER.readTree(templateBody).path("Metadata").path(MiniStackCognitoUsers.METADATA_KEY);
        if (recorded.isMissingNode()) {
            return;
        }
        MiniStackCognitoUsers.create(recorded, endpoint, region, logicalId -> cloudFormation
            .describeStackResource(r -> r.stackName(stackName).logicalResourceId(logicalId))
            .stackResourceDetail().physicalResourceId());
    }

    @Override
    public void delete(String stackName) throws IOException {
        if (!stackExists(stackName)) {
            return;
        }
        List<String> databases = deployedDatabases(stackName);
        Map<String, String> outputs = outputs(stackName);
        try {
            cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
            cloudFormation.waiter().waitUntilStackDeleteComplete(
                DescribeStacksRequest.builder().stackName(stackName).build());
        } catch (Exception e) {
            throw new IOException("Failed to delete MiniStack stack " + stackName, e);
        }
        if (!databases.isEmpty()) {
            try (MiniStackDatabaseProvisioner provisioner = new MiniStackDatabaseProvisioner(endpoint, region, credentials)) {
                provisioner.delete(databases);
            }
        }
        removeHostVolumes(outputs, MiniStackTemplateAdapter.defaultVolumeRoot());
    }

    /**
     * Deletes the host directories that stood in for the stack's EFS file systems, as deleting the
     * stack deletes them. Left behind, they would be mounted by the next stack with the same name
     * together with a new, empty database, and an application that found its own files would skip
     * its installation. File systems the template retains are not listed in the outputs and are
     * kept. Only directories under {@code root} are removed.
     */
    static void removeHostVolumes(Map<String, String> outputs, Path root) throws IOException {
        for (Path volume : hostVolumes(outputs, root)) {
            deleteRecursively(volume);
            Path normalizedRoot = root.toAbsolutePath().normalize();
            Path stackDirectory = volume.getParent();
            if (stackDirectory != null && !stackDirectory.equals(normalizedRoot) && stackDirectory.startsWith(normalizedRoot)) {
                try (var remaining = Files.list(stackDirectory)) {
                    if (remaining.findAny().isEmpty()) {
                        Files.delete(stackDirectory);
                    }
                }
            }
        }
    }

    /**
     * Empties the host directories of a stack that does not exist yet. A directory left by an earlier
     * stack of the same name (one deleted by hand, or whose destroy did not finish) holds data that
     * belongs to databases that are gone.
     */
    static void emptyHostVolumes(Map<String, String> outputs, Path root) throws IOException {
        for (Path volume : hostVolumes(outputs, root)) {
            deleteRecursively(volume);
            MiniStackTemplateAdapter.createVolumeDirectory(volume);
        }
    }

    private static Map<String, String> hostVolumesIn(String templateBody) throws IOException {
        Map<String, String> volumes = new LinkedHashMap<>();
        JsonNode outputs = MAPPER.readTree(templateBody).path("Outputs");
        outputs.properties().forEach(entry -> {
            if (entry.getKey().startsWith(HOST_VOLUME_OUTPUT_PREFIX) && entry.getValue().path("Value").isTextual()) {
                volumes.put(entry.getKey(), entry.getValue().path("Value").asText());
            }
        });
        return volumes;
    }

    /** The existing directories listed under the host volume outputs that lie below {@code root}. */
    private static List<Path> hostVolumes(Map<String, String> outputs, Path root) {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        List<Path> volumes = new ArrayList<>();
        for (Map.Entry<String, String> output : outputs.entrySet()) {
            if (!output.getKey().startsWith(HOST_VOLUME_OUTPUT_PREFIX)) {
                continue;
            }
            Path volume = Path.of(output.getValue()).toAbsolutePath().normalize();
            if (volume.startsWith(normalizedRoot) && !volume.equals(normalizedRoot) && Files.exists(volume)) {
                volumes.add(volume);
            }
        }
        return volumes;
    }

    private static void deleteRecursively(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (var files = Files.walk(directory)) {
            for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) {
                Files.delete(file);
            }
        } catch (AccessDeniedException | UncheckedIOException e) {
            removeAsRoot(directory, e);
            Files.deleteIfExists(directory);
        }
    }

    /**
     * Removes a directory whose files an application container created as another user, which the
     * deploying process cannot delete. On Linux hosts the container's user owns what it writes to a bind
     * mount; the MiniStack container's image is used to remove it, since it is already present.
     */
    private static void removeAsRoot(Path directory, Exception cause) throws IOException {
        try {
            Process lookup = new ProcessBuilder("docker", "ps", "--filter", "name=^cfc-ministack$", "--format", "{{.Image}}")
                .redirectErrorStream(true).start();
            String image = new String(lookup.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (lookup.waitFor() != 0 || image.isEmpty()) {
                throw new IOException("Could not find the MiniStack container to remove " + directory + " with", cause);
            }
            Process remove = new ProcessBuilder("docker", "run", "--rm", "--entrypoint", "sh",
                "-v", directory.toAbsolutePath() + ":/volume", image, "-c", "rm -rf /volume/* /volume/.[!.]*")
                .redirectErrorStream(true).start();
            String output = new String(remove.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (remove.waitFor() != 0) {
                throw new IOException("Could not remove " + directory + ": " + output, cause);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while removing " + directory, e);
        }
    }

    /**
     * Active {@code *-ministack} stacks (excludes DELETE*).
     */
    public List<String> listActiveMinistackStacks() {
        List<String> names = new ArrayList<>();
        String nextToken = null;
        do {
            var response = cloudFormation.listStacks(ListStacksRequest.builder()
                .nextToken(nextToken)
                .build());
            for (StackSummary summary : response.stackSummaries()) {
                String name = summary.stackName();
                String status = summary.stackStatusAsString();
                if (name == null || !name.endsWith("-ministack")) {
                    continue;
                }
                if (status != null && status.toUpperCase(Locale.ROOT).contains("DELETE")) {
                    continue;
                }
                names.add(name);
            }
            nextToken = response.nextToken();
        } while (nextToken != null);
        return List.copyOf(names);
    }

    /** Read stack tags; empty when stack missing or has none. */
    public Map<String, String> stackTags(String stackName) {
        try {
            List<Tag> tags = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks().getFirst().tags();
            Map<String, String> result = new LinkedHashMap<>();
            if (tags != null) {
                for (Tag tag : tags) {
                    if (tag.key() != null) {
                        result.put(tag.key(), tag.value());
                    }
                }
            }
            return Map.copyOf(result);
        } catch (Exception e) {
            return Map.of();
        }
    }

    /**
     * When replacing an app under a new stack name, delete other active MiniStack stacks
     * for the same {@code applicationId} (tag or {@code deployment-contexts} catalog).
     */
    public LocalSameApplicationStackReplacer.Result replaceSameApplicationStacks(
            String applicationId,
            String keepCfnStack,
            Path catalogDirectory) {
        return LocalSameApplicationStackReplacer.replace(
            applicationId,
            keepCfnStack,
            REPLACE_SAME_APP_ENV,
            DeploymentTarget.MINISTACK,
            this::listActiveMinistackStacks,
            this::stackTags,
            stack -> LocalDeploymentCatalog.applicationIdForCfnStack(
                catalogDirectory, stack, DeploymentTarget.MINISTACK),
            this::delete);
    }

    /**
     * Confirm the stack exists and return its outputs.
     *
     * @throws IOException when the stack is missing or outputs cannot be read
     */
    public Map<String, String> verifyDeployment(String stackName) throws IOException {
        if (!stackExists(stackName)) {
            throw new IOException("Stack does not exist: " + stackName);
        }
        return outputs(stackName);
    }

    @Override
    public boolean stackExists(String stackName) {
        try {
            var stacks = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(stackName).build())
                .stacks();
            if (stacks == null || stacks.isEmpty()) {
                return false;
            }
            String status = stacks.getFirst().stackStatusAsString();
            // DELETE_COMPLETE (and in-progress deletes) must not short-circuit redeploy.
            return status == null || !status.toUpperCase(Locale.ROOT).contains("DELETE");
        } catch (CloudFormationException e) {
            if (e.statusCode() == 400 || e.statusCode() == 404) {
                return false;
            }
            throw e;
        }
    }

    @Override
    public Map<String, String> outputs(String stackName) {
        List<Output> stackOutputs = cloudFormation.describeStacks(
            DescribeStacksRequest.builder().stackName(stackName).build())
            .stacks().getFirst().outputs();
        Map<String, String> result = new LinkedHashMap<>();
        for (Output output : stackOutputs) {
            result.put(output.outputKey(), output.outputValue());
        }
        return Map.copyOf(result);
    }

    private software.amazon.awssdk.services.cloudformation.model.DescribeChangeSetResponse
            waitForChangeSet(String stackName, String changeSetName) throws IOException {
        Instant deadline = Instant.now().plus(OPERATION_TIMEOUT);
        DescribeChangeSetRequest request = DescribeChangeSetRequest.builder()
            .stackName(stackName)
            .changeSetName(changeSetName)
            .build();
        while (Instant.now().isBefore(deadline)) {
            var response = cloudFormation.describeChangeSet(request);
            if (response.status() == ChangeSetStatus.CREATE_COMPLETE
                    || response.status() == ChangeSetStatus.FAILED) {
                return response;
            }
            try {
                Thread.sleep(POLL_INTERVAL);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for MiniStack change set", e);
            }
        }
        throw new IOException("Timed out waiting for MiniStack change set " + changeSetName);
    }

    private void deleteChangeSet(String stackName, String changeSetName) {
        cloudFormation.deleteChangeSet(DeleteChangeSetRequest.builder()
            .stackName(stackName)
            .changeSetName(changeSetName)
            .build());
    }

    /** The databases created for the stack, recorded in its deployed template. */
    private List<String> deployedDatabases(String stackName) {
        try {
            String deployed = cloudFormation.getTemplate(
                GetTemplateRequest.builder().stackName(stackName).build()).templateBody();
            return MiniStackDatabases.provisionedIdentifiers(MAPPER.readTree(deployed));
        } catch (Exception e) {
            return List.of();
        }
    }

    private boolean templateMatches(String stackName, String candidate) throws IOException {
        try {
            String deployed = cloudFormation.getTemplate(
                GetTemplateRequest.builder().stackName(stackName).build()).templateBody();
            return MAPPER.readTree(deployed).equals(MAPPER.readTree(candidate));
        } catch (Exception e) {
            throw new IOException("Unable to compare deployed MiniStack template", e);
        }
    }

    private String recentEvents(String stackName) {
        try {
            StringBuilder summary = new StringBuilder();
            var events = cloudFormation.describeStackEvents(
                DescribeStackEventsRequest.builder().stackName(stackName).build())
                .stackEvents();

            events.stream()
                .filter(event -> event.resourceStatusAsString() != null
                    && (event.resourceStatusAsString().contains("FAILED")
                        || event.resourceStatusAsString().contains("ROLLBACK")))
                .limit(5)
                .forEach(event -> summary.append(formatEvent(event)).append('\n'));

            if (summary.isEmpty()) {
                events.stream().limit(15).forEach(event ->
                    summary.append(formatEvent(event)).append('\n'));
            } else {
                summary.append("\nRecent events:\n");
                events.stream().limit(10).forEach(event ->
                    summary.append(formatEvent(event)).append('\n'));
            }
            return summary.toString();
        } catch (Exception e) {
            return "Unable to read stack events: " + e.getMessage();
        }
    }

    private static String formatEvent(
            software.amazon.awssdk.services.cloudformation.model.StackEvent event) {
        return "%s %s %s %s".formatted(
            event.resourceStatusAsString(),
            event.resourceType(),
            event.logicalResourceId(),
            event.resourceStatusReason() == null ? "" : event.resourceStatusReason());
    }

    private static boolean isNoOp(String reason) {
        String normalized = reason.toLowerCase(java.util.Locale.ROOT);
        return normalized.contains("didn't contain changes")
            || normalized.contains("no updates")
            || normalized.contains("no changes");
    }

    private static LocalResourceChange toChange(ResourceChange resource) {
        return new LocalResourceChange(
            resource.actionAsString(),
            resource.logicalResourceId(),
            resource.resourceType(),
            resource.replacementAsString()
        );
    }

    @Override
    public void close() {
        cloudFormation.close();
        ssm.close();
    }

    /** @deprecated use {@link LocalResourceChange}. */
    @Deprecated
    public record Change(
            String action,
            String logicalResourceId,
            String resourceType,
            String replacement) {
    }

    /** @deprecated use {@link LocalDeployResult}. */
    @Deprecated
    public record DeploymentResult(
            String stackName,
            boolean created,
            boolean noOp,
            List<Change> changes,
            Map<String, String> outputs) {
    }
}
