package com.cloudforgeci.ministack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

/** Non-interactive entry point for direct local MiniStack operations. */
public final class MiniStackCli {
    private MiniStackCli() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                "Usage: MiniStackCli <deploy|delete|verify> <stack-name> [canonical-template]");
        }

        String command = args[0];
        String stackName = args[1];
        try (MiniStackDeployer deployer = new MiniStackDeployer()) {
            switch (command) {
            case "deploy" -> {
                if (args.length != 3) {
                    throw new IllegalArgumentException("deploy requires the canonical template path");
                }
                Path canonical = Path.of(args[2]);
                String contextStackName = canonical.getFileName().toString()
                    .replace(".template.json", "");
                MiniStackDeploymentPipeline.DeployResult result =
                    MiniStackDeploymentPipeline.deploy(
                        MiniStackDeploymentPipeline.DeployRequest.of(
                            contextStackName,
                            canonical,
                            canonical.getParent()));
                System.out.println("adaptations=" + result.adaptation().adaptations().size());
                System.out.println("created=" + result.deployment().created());
                System.out.println("noOp=" + result.deployment().noOp());
                result.deployment().changes().forEach(change -> System.out.println(
                    change.action() + " " + change.resourceType() + " " + change.logicalResourceId()));
                result.deployment().outputs().forEach((key, value) ->
                    System.out.println("output." + key + "=" + value));
            }
            case "delete" -> deployer.delete(stackName);
            case "verify" -> {
                if (!deployer.stackExists(stackName)) {
                    throw new IllegalStateException("Stack not found: " + stackName);
                }
                var outputs = deployer.outputs(stackName);
                outputs.forEach((key, value) -> System.out.println(key + "=" + value));
                String url = outputs.get("MiniStackLocalUrl");
                if (url != null && Boolean.parseBoolean(
                        System.getenv().getOrDefault("MINISTACK_HTTP_VERIFY", "true"))) {
                    System.out.println("httpStatus=" + waitForHttp(url));
                }
            }
                default -> throw new IllegalArgumentException("Unknown command: " + command);
            }
        }
    }

    private static int waitForHttp(String url) throws Exception {
        HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
        Instant deadline = Instant.now().plus(Duration.ofMinutes(3));
        Exception lastFailure = null;

        while (Instant.now().isBefore(deadline)) {
            try {
                HttpResponse<Void> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build(),
                    HttpResponse.BodyHandlers.discarding()
                );
                if (response.statusCode() < 500) {
                    return response.statusCode();
                }
                lastFailure = new IllegalStateException(
                    "MiniStack endpoint returned HTTP " + response.statusCode());
            } catch (java.io.IOException e) {
                lastFailure = e;
            }
            Thread.sleep(Duration.ofSeconds(2));
        }
        throw new IllegalStateException(
            "MiniStack endpoint did not become ready within 3 minutes", lastFailure);
    }
}
