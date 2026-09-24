package com.cloudforgeci.ministack;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Disabled("Local auth deferred pending LocalStack evaluation — see docs/ministack/README.md")
class MiniStackAuthProxyTest {
    @Test
    void redirectsExchangesCodeAndProxiesAuthenticatedRequest() throws Exception {
        HttpServer oidc = HttpServer.create(new InetSocketAddress(0), 0);
        oidc.createContext("/token", exchange -> {
            byte[] body = "{\"access_token\":\"test-token\",\"token_type\":\"Bearer\"}"
                .getBytes(StandardCharsets.UTF_8);
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        oidc.start();

        HttpServer upstream = HttpServer.create(new InetSocketAddress(0), 0);
        upstream.createContext("/", exchange -> {
            byte[] body = "protected application".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();

        try {
            int proxyPort = freePort();
            URI proxyUri = URI.create("http://localhost:" + proxyPort);
            MiniStackAuthProxy.Config config = new MiniStackAuthProxy.Config(
                proxyPort,
                URI.create("http://localhost:" + upstream.getAddress().getPort()),
                "http://localhost:" + oidc.getAddress().getPort() + "/authorize",
                URI.create("http://localhost:" + oidc.getAddress().getPort() + "/token"),
                "client",
                "secret",
                proxyUri.resolve("/oauth2/callback")
            );

            try (MiniStackAuthProxy proxy = new MiniStackAuthProxy(config)) {
                proxy.start();
                HttpClient client = HttpClient.newBuilder()
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();

                HttpResponse<String> login = client.send(
                    HttpRequest.newBuilder(proxyUri.resolve("/admin")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(302, login.statusCode());
                assertTrue(login.headers().firstValue("Location").orElseThrow()
                    .contains("response_type=code"));

                HttpResponse<String> callback = client.send(
                    HttpRequest.newBuilder(proxyUri.resolve("/oauth2/callback?code=valid"))
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(302, callback.statusCode());
                String cookie = callback.headers().firstValue("Set-Cookie").orElseThrow()
                    .split(";", 2)[0];

                HttpResponse<String> protectedResponse = client.send(
                    HttpRequest.newBuilder(proxyUri.resolve("/admin"))
                        .header("Cookie", cookie)
                        .GET().build(),
                    HttpResponse.BodyHandlers.ofString());
                assertEquals(200, protectedResponse.statusCode());
                assertEquals("protected application", protectedResponse.body());
            }
        } finally {
            oidc.stop(0);
            upstream.stop(0);
        }
    }

    private static int freePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
