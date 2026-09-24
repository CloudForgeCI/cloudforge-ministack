package com.cloudforgeci.ministack;

import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.LocalDeployResult;
import com.cloudforge.core.local.LocalDeployer;
import com.cloudforge.core.local.LocalDeploymentCatalog;
import com.cloudforge.core.local.LocalResourceChange;
import com.cloudforge.core.local.LocalSameApplicationStackReplacer;
import com.fasterxml.jackson.databind.ObjectMapper;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
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

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
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
    private static final Duration OPERATION_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private final CloudFormationClient cloudFormation;

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
        cloudFormation = CloudFormationClient.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create("test", "test")))
            .build();
    }

    @Override
    public LocalDeployResult deploy(String stackName, Path template) throws IOException {
        boolean exists = stackExists(stackName);
        String templateBody = Files.readString(template);
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

        return new LocalDeployResult(stackName, !exists, false, changes, outputs(stackName));
    }

    @Override
    public void delete(String stackName) throws IOException {
        if (!stackExists(stackName)) {
            return;
        }
        try {
            cloudFormation.deleteStack(DeleteStackRequest.builder().stackName(stackName).build());
            cloudFormation.waiter().waitUntilStackDeleteComplete(
                DescribeStacksRequest.builder().stackName(stackName).build());
        } catch (Exception e) {
            throw new IOException("Failed to delete MiniStack stack " + stackName, e);
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
