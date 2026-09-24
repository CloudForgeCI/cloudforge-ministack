package com.cloudforgeci.ministack;

import com.cloudforge.core.local.LocalAuthRuntime;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Reconciles local processes required by the deployed MiniStack template.
 * This intentionally uses direct process calls rather than repository scripts.
 */
public final class MiniStackLocalRuntime implements LocalAuthRuntime {
    public static final MiniStackLocalRuntime INSTANCE = new MiniStackLocalRuntime();

    private static final String PROXY_CLASS =
        "com.cloudforgeci.ministack.MiniStackAuthProxy";
    private static final Duration COMMAND_TIMEOUT = Duration.ofMinutes(2);

    private MiniStackLocalRuntime() {
    }

    @Override
    public void reconcile(boolean authenticationEnabled, String upstream)
            throws IOException {
        if (!Boolean.parseBoolean(
                System.getenv().getOrDefault("MINISTACK_AUTH_AUTOSTART", "false"))) {
            System.out.println("   MiniStack auth runtime auto-start is disabled");
            return;
        }

        Path projectRoot = projectRoot();
        if (authenticationEnabled) {
            if (upstream == null || upstream.isBlank()) {
                throw new IOException(
                    "Authenticated MiniStack deployment has no MiniStackApplicationUrl output");
            }
            startMockOidc(projectRoot);
            startProxy(projectRoot, URI.create(upstream));
            System.out.println("   ✅ Local auth runtime: http://localhost:"
                + authPort());
        } else {
            stopProxy(projectRoot);
            stopMockOidc(projectRoot);
            System.out.println("   Local auth runtime stopped (authentication disabled)");
        }
    }

    private static void startMockOidc(Path projectRoot) throws IOException {
        if (!managedMockOidc()) {
            return;
        }
        run(List.of(
            "docker", "compose",
            "-f", projectRoot.resolve("docker-compose.yml").toString(),
            "up", "-d", "mock-oidc"
        ), projectRoot);
        waitForHttp(URI.create("http://localhost:3001/health"), Duration.ofSeconds(30));
    }

    private static void stopMockOidc(Path projectRoot) throws IOException {
        if (!managedMockOidc()) {
            return;
        }
        run(List.of(
            "docker", "compose",
            "-f", projectRoot.resolve("docker-compose.yml").toString(),
            "stop", "mock-oidc"
        ), projectRoot);
    }

    private static void startProxy(Path projectRoot, URI upstream) throws IOException {
        URI health = URI.create("http://localhost:" + authPort()
            + "/_ministack/auth/health");
        if (isHealthy(health)) {
            Path stateFile = stateFile(projectRoot);
            if (!Files.exists(pidFile(projectRoot))) {
                System.out.println(
                    "   Using externally started MiniStack auth proxy on port " + authPort());
                return;
            }
            if (Files.exists(stateFile)
                    && upstream.toString().equals(Files.readString(stateFile).trim())) {
                return;
            }
        }

        stopProxy(projectRoot);
        Path runtimeDirectory = projectRoot.resolve("cfc-testing/target/ministack-runtime");
        Files.createDirectories(runtimeDirectory);
        Path log = runtimeDirectory.resolve("auth-proxy.log");

        List<String> command = new ArrayList<>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-cp");
        command.add(absoluteClasspath());
        command.add(PROXY_CLASS);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(projectRoot.resolve("cfc-testing").toFile());
        builder.redirectErrorStream(true);
        builder.redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        builder.environment().put("MINISTACK_AUTH_PORT", Integer.toString(authPort()));
        builder.environment().put("MINISTACK_AUTH_UPSTREAM", upstream.toString());
        builder.environment().put(
            "MINISTACK_OIDC_AUTHORIZATION_ENDPOINT", authorizationEndpoint());
        builder.environment().put(
            "MINISTACK_OIDC_TOKEN_ENDPOINT",
            System.getenv().getOrDefault(
                "MINISTACK_OIDC_TOKEN_ENDPOINT", "http://localhost:3001/oauth/token"));
        builder.environment().put(
            "MINISTACK_OIDC_CLIENT_ID",
            System.getenv().getOrDefault("MINISTACK_OIDC_CLIENT_ID", "cfc-client"));
        builder.environment().put(
            "MINISTACK_OIDC_CLIENT_SECRET",
            System.getenv().getOrDefault("MINISTACK_OIDC_CLIENT_SECRET", "cfc-secret"));
        builder.environment().put(
            "MINISTACK_OIDC_REDIRECT_URI",
            System.getenv().getOrDefault(
                "MINISTACK_OIDC_REDIRECT_URI",
                "http://localhost:" + authPort() + "/oauth2/callback"));

        Process process = builder.start();
        Files.writeString(
            pidFile(projectRoot),
            Long.toString(process.pid()),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
        try {
            waitForHttp(health, Duration.ofSeconds(30));
            Files.writeString(
                stateFile(projectRoot),
                upstream.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (IOException e) {
            process.destroyForcibly();
            Files.deleteIfExists(pidFile(projectRoot));
            throw e;
        }
    }

    private static void stopProxy(Path projectRoot) throws IOException {
        Path pidFile = pidFile(projectRoot);
        if (!Files.exists(pidFile)) {
            return;
        }
        try {
            long pid = Long.parseLong(Files.readString(pidFile).trim());
            ProcessHandle.of(pid).ifPresent(process -> {
                String commandLine = process.info().commandLine().orElse("");
                if (commandLine.contains(PROXY_CLASS)) {
                    process.destroy();
                }
            });
        } catch (NumberFormatException ignored) {
            // A corrupt local PID file is safe to discard.
        } finally {
            Files.deleteIfExists(pidFile);
            Files.deleteIfExists(stateFile(projectRoot));
        }
    }

    private static Path pidFile(Path projectRoot) {
        return projectRoot.resolve("cfc-testing/target/ministack-runtime/auth-proxy.pid");
    }

    private static Path stateFile(Path projectRoot) {
        return projectRoot.resolve(
            "cfc-testing/target/ministack-runtime/auth-proxy-upstream");
    }

    private static int authPort() {
        String raw = System.getenv().getOrDefault("MINISTACK_AUTH_PORT", "4180");
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException e) {
            return 4180;
        }
    }

    private static String absoluteClasspath() {
        Path launchDirectory = Path.of("").toAbsolutePath().normalize();
        return Arrays.stream(System.getProperty("java.class.path")
                .split(java.io.File.pathSeparator))
            .map(Path::of)
            .map(path -> path.isAbsolute() ? path : launchDirectory.resolve(path).normalize())
            .map(Path::toString)
            .collect(java.util.stream.Collectors.joining(java.io.File.pathSeparator));
    }

    private static String authorizationEndpoint() {
        return System.getenv().getOrDefault(
            "MINISTACK_OIDC_AUTHORIZATION_ENDPOINT",
            "http://localhost:3001/oauth/authorize");
    }

    private static boolean managedMockOidc() {
        return Boolean.parseBoolean(
            System.getenv().getOrDefault("MINISTACK_MOCK_OIDC_MANAGED", "true"));
    }

    private static void waitForHttp(URI uri, Duration timeout) throws IOException {
        Instant deadline = Instant.now().plus(timeout);
        while (Instant.now().isBefore(deadline)) {
            if (isHealthy(uri)) {
                return;
            }
            try {
                Thread.sleep(Duration.ofMillis(500));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted while waiting for " + uri, e);
            }
        }
        throw new IOException("Timed out waiting for " + uri);
    }

    private static boolean isHealthy(URI uri) {
        try {
            HttpResponse<Void> response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(2)).GET().build(),
                HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void run(List<String> command, Path directory) throws IOException {
        Process process = new ProcessBuilder(command)
            .directory(directory.toFile())
            .inheritIO()
            .start();
        try {
            if (!process.waitFor(COMMAND_TIMEOUT.toMillis(),
                    java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                throw new IOException("Command timed out: " + String.join(" ", command));
            }
            if (process.exitValue() != 0) {
                throw new IOException("Command failed: " + String.join(" ", command));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("Interrupted while running local runtime command", e);
        }
    }

    private static Path projectRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (Path candidate = current; candidate != null; candidate = candidate.getParent()) {
            if (Files.exists(candidate.resolve("docker-compose.yml"))
                    && Files.isDirectory(candidate.resolve("cfc-testing"))) {
                return candidate;
            }
        }
        throw new IOException("Unable to locate project root for MiniStack local runtime");
    }
}
