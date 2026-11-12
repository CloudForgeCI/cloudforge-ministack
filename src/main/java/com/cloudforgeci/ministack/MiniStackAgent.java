package com.cloudforgeci.ministack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * Deploys and deletes MiniStack stacks on behalf of callers that cannot do it themselves, such as
 * CloudForge Manager running as a container inside the emulator. It runs where the emulator's Docker
 * daemon is, so it can manage the database containers and host the local auth proxy, and it takes a
 * template that is already synthesized.
 *
 * <p>All calls except {@code GET /v1/healthz} need {@code Authorization: Bearer <token>}.
 * Deploying and deleting are asynchronous: they answer {@code 202} with a job id to poll.</p>
 *
 * <pre>
 * POST   /v1/stacks            {stackName, applicationId, region, replaceSameApplication, template}
 * DELETE /v1/stacks/{name}     ?region=us-east-1
 * GET    /v1/jobs/{id}         {state: RUNNING|COMPLETE|FAILED, outputs, changes, error}
 * GET    /v1/healthz
 * </pre>
 */
public final class MiniStackAgent implements AutoCloseable {
    private static final Logger LOG = Logger.getLogger(MiniStackAgent.class.getName());
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int MAX_BODY_BYTES = 32 * 1024 * 1024;
    private static final int MAX_JOBS = 200;
    private static final String DEFAULT_REGION = "us-east-1";

    /** The MiniStack operations the agent performs. */
    interface Backend {
        Outcome deploy(DeployRequest request) throws IOException;

        void delete(String stackName, String region) throws IOException;
    }

    record DeployRequest(
            String stackName, String applicationId, String region, boolean replaceSameApplication,
            JsonNode template) {
    }

    record Outcome(Map<String, String> outputs, List<String> changes, boolean created, boolean noOp) {
    }

    private record Job(
            String id, String kind, String stackName, Instant startedAt,
            java.util.concurrent.atomic.AtomicReference<State> state) {
    }

    private record State(String name, Outcome outcome, String error) {
    }

    private final Backend backend;
    private final byte[] token;
    private final Map<String, Job> jobs = new ConcurrentHashMap<>();
    private final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
    private HttpServer server;

    MiniStackAgent(Backend backend, String token) {
        this.backend = backend;
        this.token = token.getBytes(StandardCharsets.UTF_8);
    }

    /** Starts listening; returns the port. */
    int start(String bindAddress, int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress(bindAddress, port), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
        executor.shutdownNow();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod();
            if ("GET".equals(method) && "/v1/healthz".equals(path)) {
                respond(exchange, 200, MAPPER.createObjectNode().put("status", "ok"));
            } else if (!authorized(exchange)) {
                respond(exchange, 401, error("missing or invalid bearer token"));
            } else if ("POST".equals(method) && "/v1/stacks".equals(path)) {
                deploy(exchange);
            } else if ("DELETE".equals(method) && path.startsWith("/v1/stacks/")) {
                delete(exchange, path.substring("/v1/stacks/".length()));
            } else if ("GET".equals(method) && path.startsWith("/v1/jobs/")) {
                job(exchange, path.substring("/v1/jobs/".length()));
            } else {
                respond(exchange, 404, error("no such route: " + method + " " + path));
            }
        } catch (IllegalArgumentException e) {
            respond(exchange, 400, error(e.getMessage()));
        } catch (RuntimeException e) {
            LOG.warning("Agent request failed: " + e);
            respond(exchange, 500, error("internal error"));
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return false;
        }
        return MessageDigest.isEqual(header.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8), token);
    }

    private void deploy(HttpExchange exchange) throws IOException {
        JsonNode body = readJson(exchange);
        String stackName = required(body, "stackName");
        JsonNode template = body.path("template");
        if (!template.isObject()) {
            throw new IllegalArgumentException("template is required and must be a JSON object");
        }
        DeployRequest request = new DeployRequest(
            stackName,
            body.path("applicationId").asText(null),
            body.path("region").asText(DEFAULT_REGION),
            body.path("replaceSameApplication").asBoolean(false),
            template);
        Job job = newJob("deploy", stackName);
        executor.submit(() -> run(job, () -> backend.deploy(request)));
        respond(exchange, 202, MAPPER.createObjectNode().put("jobId", job.id()));
    }

    private void delete(HttpExchange exchange, String stackName) throws IOException {
        if (stackName.isBlank() || stackName.contains("/")) {
            throw new IllegalArgumentException("stack name is required");
        }
        String region = DEFAULT_REGION;
        String query = exchange.getRequestURI().getQuery();
        if (query != null) {
            for (String pair : query.split("&")) {
                if (pair.startsWith("region=") && pair.length() > "region=".length()) {
                    region = pair.substring("region=".length());
                }
            }
        }
        String effectiveRegion = region;
        Job job = newJob("delete", stackName);
        executor.submit(() -> run(job, () -> {
            backend.delete(stackName, effectiveRegion);
            return new Outcome(Map.of(), List.of(), false, false);
        }));
        respond(exchange, 202, MAPPER.createObjectNode().put("jobId", job.id()));
    }

    private void job(HttpExchange exchange, String id) throws IOException {
        Job job = jobs.get(id);
        if (job == null) {
            respond(exchange, 404, error("no such job: " + id));
            return;
        }
        State state = job.state().get();
        ObjectNode view = MAPPER.createObjectNode()
            .put("jobId", job.id())
            .put("kind", job.kind())
            .put("stackName", job.stackName())
            .put("startedAt", job.startedAt().toString())
            .put("state", state.name());
        if (state.outcome() != null) {
            view.set("outputs", MAPPER.valueToTree(state.outcome().outputs()));
            view.set("changes", MAPPER.valueToTree(state.outcome().changes()));
            view.put("created", state.outcome().created());
            view.put("noOp", state.outcome().noOp());
        }
        if (state.error() != null) {
            view.put("error", state.error());
        }
        respond(exchange, 200, view);
    }

    @FunctionalInterface
    private interface Work {
        Outcome run() throws IOException;
    }

    private void run(Job job, Work work) {
        try {
            job.state().set(new State("COMPLETE", work.run(), null));
        } catch (Exception e) {
            LOG.warning(job.kind() + " " + job.stackName() + " failed: " + e);
            job.state().set(new State("FAILED", null, e.getMessage() != null ? e.getMessage() : e.toString()));
        }
    }

    private Job newJob(String kind, String stackName) {
        if (jobs.size() >= MAX_JOBS) {
            jobs.values().stream()
                .filter(j -> !"RUNNING".equals(j.state().get().name()))
                .min(java.util.Comparator.comparing(Job::startedAt))
                .ifPresent(oldest -> jobs.remove(oldest.id()));
        }
        Job job = new Job(UUID.randomUUID().toString(), kind, stackName, Instant.now(),
            new java.util.concurrent.atomic.AtomicReference<>(new State("RUNNING", null, null)));
        jobs.put(job.id(), job);
        return job;
    }

    private static String required(JsonNode body, String field) {
        String value = body.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static JsonNode readJson(HttpExchange exchange) throws IOException {
        byte[] bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
        if (bytes.length > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("request body is larger than " + MAX_BODY_BYTES + " bytes");
        }
        try {
            JsonNode body = MAPPER.readTree(bytes);
            if (body == null || !body.isObject()) {
                throw new IllegalArgumentException("request body must be a JSON object");
            }
            return body;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalArgumentException("request body is not valid JSON");
        }
    }

    private static ObjectNode error(String message) {
        return MAPPER.createObjectNode().put("error", message);
    }

    private static void respond(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = MAPPER.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
    }

    /**
     * The token callers present. {@code CFC_AGENT_TOKEN} wins; otherwise one is generated once and kept in
     * {@code ministack-agent.token} under the agent's home, readable only by the current user.
     */
    static String resolveToken(String fromEnvironment, Path home) throws IOException {
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return fromEnvironment.trim();
        }
        Path file = home.resolve("ministack-agent.token");
        if (Files.exists(file)) {
            return Files.readString(file).trim();
        }
        Files.createDirectories(home);
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        String token = HexFormat.of().formatHex(random);
        Files.writeString(file, token + "\n");
        try {
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-------"));
        } catch (UnsupportedOperationException e) {
            LOG.warning("Could not restrict permissions on " + file);
        }
        return token;
    }

    /**
     * The token a running agent would accept: {@code CFC_AGENT_TOKEN}, else the one kept under the agent's
     * home. Empty when neither exists yet, which means no agent has been started on this machine.
     */
    static java.util.Optional<String> existingToken(String fromEnvironment, Path home) {
        if (fromEnvironment != null && !fromEnvironment.isBlank()) {
            return java.util.Optional.of(fromEnvironment.trim());
        }
        Path file = home.resolve("ministack-agent.token");
        try {
            return Files.exists(file) ? java.util.Optional.of(Files.readString(file).trim()) : java.util.Optional.empty();
        } catch (IOException e) {
            return java.util.Optional.empty();
        }
    }

    /** The agent's home: {@code CFC_AGENT_HOME}, else {@code ~/.cloudforge}. */
    static Path defaultHome(Map<String, String> env) {
        return Path.of(env.getOrDefault("CFC_AGENT_HOME", System.getProperty("user.home") + "/.cloudforge"));
    }

    /**
     * Starts the agent. Settings: {@code CFC_AGENT_PORT} (4570), {@code CFC_AGENT_BIND} (127.0.0.1; use
     * 0.0.0.0 where containers reach the host by its network address), {@code CFC_AGENT_TOKEN},
     * {@code CFC_AGENT_HOME} (~/.cloudforge).
     */
    public static void main(String[] args) throws Exception {
        Map<String, String> env = System.getenv();
        Path home = defaultHome(env);
        String token = resolveToken(env.get("CFC_AGENT_TOKEN"), home);
        int port = Integer.parseInt(env.getOrDefault("CFC_AGENT_PORT", "4570"));
        String bind = env.getOrDefault("CFC_AGENT_BIND", "127.0.0.1");
        MiniStackAgent agent = new MiniStackAgent(new MiniStackAgentBackend(home.resolve("ministack-agent")), token);
        int bound = agent.start(bind, port);
        System.out.println("MiniStack agent listening on " + bind + ":" + bound
            + " (token in " + home.resolve("ministack-agent.token") + " unless CFC_AGENT_TOKEN is set)");
        Runtime.getRuntime().addShutdownHook(new Thread(agent::close));
        Thread.currentThread().join();
    }
}
