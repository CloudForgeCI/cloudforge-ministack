package com.cloudforgeci.ministack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudforgeci.ministack.MiniStackAgent.DeployRequest;
import com.cloudforgeci.ministack.MiniStackAgent.Outcome;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MiniStackAgentTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOKEN = "test-token";

    private final List<DeployRequest> deployed = new CopyOnWriteArrayList<>();
    private final List<String> deleted = new CopyOnWriteArrayList<>();
    private volatile IOException failure;
    private volatile CountDownLatch release = new CountDownLatch(0);

    private MiniStackAgent agent;
    private String base;
    private final HttpClient client = HttpClient.newHttpClient();

    @BeforeEach
    void start() throws IOException {
        agent = new MiniStackAgent(new MiniStackAgent.Backend() {
            @Override
            public Outcome deploy(DeployRequest request) throws IOException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                if (failure != null) {
                    throw failure;
                }
                deployed.add(request);
                return new Outcome(Map.of("ApplicationUrl", "http://app"), List.of("Add AWS::ECS::Service Svc"), true, false);
            }

            @Override
            public void delete(String stackName, String region) {
                deleted.add(stackName + "@" + region);
            }
        }, TOKEN);
        base = "http://127.0.0.1:" + agent.start("127.0.0.1", 0);
    }

    @AfterEach
    void stop() {
        agent.close();
    }

    private HttpResponse<String> send(String method, String path, String body, String token) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(base + path));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        request.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode awaitJob(String jobId) throws Exception {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            JsonNode view = MAPPER.readTree(send("GET", "/v1/jobs/" + jobId, null, TOKEN).body());
            if (!"RUNNING".equals(view.path("state").asText())) {
                return view;
            }
            Thread.sleep(25);
        }
        throw new AssertionError("job " + jobId + " did not finish");
    }

    private static final String DEPLOY_BODY =
        "{\"stackName\":\"mgr-a\",\"applicationId\":\"joomla\",\"region\":\"us-west-2\","
            + "\"replaceSameApplication\":true,\"template\":{\"Resources\":{}}}";

    @Test
    void healthNeedsNoToken() throws Exception {
        HttpResponse<String> response = send("GET", "/v1/healthz", null, null);
        assertEquals(200, response.statusCode());
        assertEquals("ok", MAPPER.readTree(response.body()).path("status").asText());
    }

    @Test
    void everyOtherRouteRejectsAMissingOrWrongToken() throws Exception {
        assertEquals(401, send("POST", "/v1/stacks", DEPLOY_BODY, null).statusCode());
        assertEquals(401, send("POST", "/v1/stacks", DEPLOY_BODY, "wrong").statusCode());
        assertEquals(401, send("GET", "/v1/jobs/x", null, null).statusCode());
        assertEquals(401, send("DELETE", "/v1/stacks/mgr-a", null, "wrong").statusCode());
        assertTrue(deployed.isEmpty());
    }

    @Test
    void deployRunsInTheBackgroundAndReportsItsOutputs() throws Exception {
        release = new CountDownLatch(1);
        HttpResponse<String> accepted = send("POST", "/v1/stacks", DEPLOY_BODY, TOKEN);
        assertEquals(202, accepted.statusCode());
        String jobId = MAPPER.readTree(accepted.body()).path("jobId").asText();

        JsonNode running = MAPPER.readTree(send("GET", "/v1/jobs/" + jobId, null, TOKEN).body());
        assertEquals("RUNNING", running.path("state").asText());

        release.countDown();
        JsonNode done = awaitJob(jobId);
        assertEquals("COMPLETE", done.path("state").asText());
        assertEquals("http://app", done.path("outputs").path("ApplicationUrl").asText());
        assertEquals("Add AWS::ECS::Service Svc", done.path("changes").get(0).asText());
        assertTrue(done.path("created").asBoolean());

        DeployRequest request = deployed.getFirst();
        assertEquals("mgr-a", request.stackName());
        assertEquals("joomla", request.applicationId());
        assertEquals("us-west-2", request.region());
        assertTrue(request.replaceSameApplication());
    }

    @Test
    void aFailedDeployIsReportedWithItsMessage() throws Exception {
        failure = new IOException("MiniStack database x did not become available");
        String jobId = MAPPER.readTree(send("POST", "/v1/stacks", DEPLOY_BODY, TOKEN).body()).path("jobId").asText();
        JsonNode done = awaitJob(jobId);
        assertEquals("FAILED", done.path("state").asText());
        assertEquals("MiniStack database x did not become available", done.path("error").asText());
        assertFalse(done.has("outputs"));
    }

    @Test
    void deployDefaultsTheRegionAndRejectsABadRequest() throws Exception {
        String ok = "{\"stackName\":\"s\",\"template\":{\"Resources\":{}}}";
        String jobId = MAPPER.readTree(send("POST", "/v1/stacks", ok, TOKEN).body()).path("jobId").asText();
        awaitJob(jobId);
        assertEquals("us-east-1", deployed.getFirst().region());
        assertFalse(deployed.getFirst().replaceSameApplication());

        assertEquals(400, send("POST", "/v1/stacks", "{\"template\":{}}", TOKEN).statusCode(), "no stackName");
        assertEquals(400, send("POST", "/v1/stacks", "{\"stackName\":\"s\"}", TOKEN).statusCode(), "no template");
        assertEquals(400, send("POST", "/v1/stacks", "{\"stackName\":\"s\",\"template\":[]}", TOKEN).statusCode());
        assertEquals(400, send("POST", "/v1/stacks", "not json", TOKEN).statusCode());
    }

    @Test
    void deleteRunsAsAJobInTheRequestedRegion() throws Exception {
        HttpResponse<String> accepted = send("DELETE", "/v1/stacks/mgr-a?region=eu-west-1", null, TOKEN);
        assertEquals(202, accepted.statusCode());
        JsonNode done = awaitJob(MAPPER.readTree(accepted.body()).path("jobId").asText());
        assertEquals("COMPLETE", done.path("state").asText());
        assertEquals("delete", done.path("kind").asText());
        assertEquals(List.of("mgr-a@eu-west-1"), deleted);
    }

    @Test
    void unknownRoutesAndJobsAre404() throws Exception {
        assertEquals(404, send("GET", "/v1/jobs/none", null, TOKEN).statusCode());
        assertEquals(404, send("GET", "/v1/other", null, TOKEN).statusCode());
        assertEquals(404, send("PUT", "/v1/stacks", "{}", TOKEN).statusCode());
    }

    @Test
    void tokenIsTakenFromTheEnvironmentOtherwiseGeneratedOnceAndKept(@TempDir Path home) throws IOException {
        assertEquals("from-env", MiniStackAgent.resolveToken(" from-env ", home));
        assertFalse(Files.exists(home.resolve("ministack-agent.token")), "an explicit token is not stored");

        String generated = MiniStackAgent.resolveToken(null, home);
        assertEquals(64, generated.length());
        assertEquals(generated, MiniStackAgent.resolveToken("", home), "the same token is reused");
        assertEquals(generated, Files.readString(home.resolve("ministack-agent.token")).trim());
    }

    @Test
    void backendRefusesATemplateMiniStackCannotHostBeforeCreatingAnything(@TempDir Path data) throws Exception {
        JsonNode aurora = MAPPER.readTree("""
            {"Resources": {"Cluster": {"Type": "AWS::RDS::DBCluster", "Properties": {"Engine": "aurora-mysql"}}}}
            """);
        MiniStackAgentBackend backend = new MiniStackAgentBackend(data);

        IOException refused = org.junit.jupiter.api.Assertions.assertThrows(IOException.class, () ->
            backend.deploy(new DeployRequest("aurora-app", "gitea", "us-east-1", false, aurora)));

        assertTrue(refused.getMessage().contains("AWS::RDS::DBCluster"), refused.getMessage());
        try (var left = Files.list(data)) {
            assertEquals(0, left.count(), "the working directory is removed");
        }
    }

    @Test
    void anExistingTokenIsFoundFromTheEnvironmentOrTheAgentsHomeOtherwiseNone(@TempDir Path home) throws IOException {
        assertTrue(MiniStackAgent.existingToken(null, home).isEmpty(), "no agent has been started here");
        assertEquals("from-env", MiniStackAgent.existingToken(" from-env ", home).orElseThrow());

        String generated = MiniStackAgent.resolveToken(null, home);
        assertEquals(generated, MiniStackAgent.existingToken("", home).orElseThrow());
    }

    @Test
    void theAgentsHomeDefaultsToTheCloudforgeDirectoryOfTheUser() {
        assertEquals(Path.of("/custom"), MiniStackAgent.defaultHome(Map.of("CFC_AGENT_HOME", "/custom")));
        assertTrue(MiniStackAgent.defaultHome(Map.of()).endsWith(".cloudforge"));
    }
}
