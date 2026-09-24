package com.cloudforgeci.ministack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Local-only OIDC authorization-code proxy used where MiniStack ALB cannot execute
 * AWS authenticate-oidc/authenticate-cognito listener actions.
 *
 * <p>After login, proxied requests include synthetic {@code x-amzn-oidc-*} headers so
 * apps using {@code authMode=alb-oidc} (e.g. CloudForge Manager) can upsert identity
 * the same way they would behind a real Cognito ALB.</p>
 */
public final class MiniStackAuthProxy implements AutoCloseable {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String COOKIE_NAME = "CFC_MINISTACK_SESSION";

    private final Config config;
    private final HttpClient client;
    private final Map<String, SessionPrincipal> sessions = new ConcurrentHashMap<>();
    private HttpServer server;

    record SessionPrincipal(String subject, String oidcDataJwt) {
    }

    public MiniStackAuthProxy(Config config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    }

    public URI start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(config.listenPort()), 0);
        server.createContext("/", this::handle);
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.start();
        return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if ("/_ministack/auth/health".equals(exchange.getRequestURI().getPath())) {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            } else if ("/oauth2/callback".equals(exchange.getRequestURI().getPath())) {
                handleCallback(exchange);
            } else if (hasSession(exchange)) {
                proxy(exchange);
            } else {
                redirectToLogin(exchange);
            }
        } catch (Exception e) {
            byte[] body = ("MiniStack auth proxy error: " + e.getMessage())
                .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(502, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }
    }

    private void redirectToLogin(HttpExchange exchange) throws IOException {
        String state = UUID.randomUUID().toString();
        String location = config.authorizationEndpoint()
            + "?response_type=code"
            + "&client_id=" + encode(config.clientId())
            + "&redirect_uri=" + encode(config.redirectUri().toString())
            + "&scope=" + encode("openid email profile")
            + "&state=" + encode(state);
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private void handleCallback(HttpExchange exchange) throws Exception {
        String code = queryParameter(exchange.getRequestURI().getRawQuery(), "code");
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("OIDC callback did not contain a code");
        }

        String form = "grant_type=authorization_code"
            + "&code=" + encode(code)
            + "&client_id=" + encode(config.clientId())
            + "&client_secret=" + encode(config.clientSecret())
            + "&redirect_uri=" + encode(config.redirectUri().toString());
        HttpResponse<String> tokenResponse = client.send(
            HttpRequest.newBuilder(config.tokenEndpoint())
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form))
                .build(),
            HttpResponse.BodyHandlers.ofString()
        );
        if (tokenResponse.statusCode() / 100 != 2) {
            throw new IllegalStateException(
                "OIDC token exchange returned HTTP " + tokenResponse.statusCode());
        }
        JsonNode token = MAPPER.readTree(tokenResponse.body());
        if (!token.hasNonNull("access_token") && !token.hasNonNull("id_token")) {
            throw new IllegalStateException("OIDC token response contained no token");
        }

        SessionPrincipal principal = principalFromToken(token);
        String session = UUID.randomUUID().toString();
        sessions.put(session, principal);
        exchange.getResponseHeaders().add(
            "Set-Cookie",
            COOKIE_NAME + "=" + session + "; Path=/; HttpOnly; SameSite=Lax"
        );
        exchange.getResponseHeaders().set("Location", "/");
        exchange.sendResponseHeaders(302, -1);
        exchange.close();
    }

    private Optional<SessionPrincipal> sessionPrincipal(HttpExchange exchange) {
        List<String> cookies = exchange.getRequestHeaders().getOrDefault("Cookie", List.of());
        return cookies.stream()
            .flatMap(header -> List.of(header.split(";")).stream())
            .map(String::trim)
            .filter(cookie -> cookie.startsWith(COOKIE_NAME + "="))
            .map(cookie -> cookie.substring(COOKIE_NAME.length() + 1))
            .map(sessions::get)
            .filter(p -> p != null)
            .findFirst();
    }

    private boolean hasSession(HttpExchange exchange) {
        return sessionPrincipal(exchange).isPresent();
    }

    private void proxy(HttpExchange exchange) throws Exception {
        URI target = config.upstream().resolve(exchange.getRequestURI().toString());
        HttpRequest.Builder request = HttpRequest.newBuilder(target)
            .timeout(Duration.ofSeconds(30))
            .method(
                exchange.getRequestMethod(),
                exchange.getRequestBody() == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(exchange.getRequestBody().readAllBytes())
            );
        exchange.getRequestHeaders().forEach((name, values) -> {
            if (isForwardableRequestHeader(name)) {
                values.forEach(value -> request.header(name, value));
            }
        });
        sessionPrincipal(exchange).ifPresent(principal -> {
            request.header("x-amzn-oidc-identity", principal.subject());
            request.header("x-amzn-oidc-data", principal.oidcDataJwt());
        });

        HttpResponse<byte[]> response =
            client.send(request.build(), HttpResponse.BodyHandlers.ofByteArray());
        response.headers().map().forEach((name, values) -> {
            if (!isHopByHopHeader(name)) {
                values.forEach(value -> exchange.getResponseHeaders().add(name, value));
            }
        });
        exchange.sendResponseHeaders(response.statusCode(), response.body().length);
        exchange.getResponseBody().write(response.body());
        exchange.close();
    }

    /**
     * Headers safe to copy onto {@link HttpRequest}. Java's HttpClient rejects
     * hop-by-hop / restricted names such as {@code Connection}.
     */
    static boolean isForwardableRequestHeader(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("x-amzn-oidc-")) {
            return false; // injected from session
        }
        return switch (lower) {
            case "connection", "content-length", "expect", "host", "keep-alive",
                 "proxy-authenticate", "proxy-authorization", "te", "trailer",
                 "transfer-encoding", "upgrade", "cookie" -> false;
            default -> true;
        };
    }

    static SessionPrincipal principalFromToken(JsonNode token) {
        String idToken = token.hasNonNull("id_token") ? token.get("id_token").asText() : null;
        if (idToken != null && !idToken.isBlank()) {
            JsonNode claims = decodeJwtPayload(idToken);
            if (claims != null) {
                String subject = text(claims, "sub");
                if (subject == null || subject.isBlank()) {
                    subject = "ministack-oidc-user";
                }
                // Prefer the real id_token as x-amzn-oidc-data (ALB shape)
                return new SessionPrincipal(subject, idToken.trim());
            }
        }
        String fallbackClaims = """
            {"sub":"ministack-oidc-user","email":"dev@cloudforgeci.com","cognito:username":"dev-user","name":"CloudForge Developer","cognito:groups":["ManagerAdmins"]}
            """;
        String payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(fallbackClaims.getBytes(StandardCharsets.UTF_8));
        return new SessionPrincipal("ministack-oidc-user", "e30." + payload + ".sig");
    }

    private static JsonNode decodeJwtPayload(String jwt) {
        try {
            String[] parts = jwt.trim().split("\\.");
            if (parts.length < 2) {
                return null;
            }
            String padded = parts[1];
            int mod = padded.length() % 4;
            if (mod > 0) {
                padded = padded + "====".substring(mod);
            }
            return MAPPER.readTree(Base64.getUrlDecoder().decode(padded));
        } catch (Exception e) {
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual()) {
            return null;
        }
        String s = value.asText().trim();
        return s.isEmpty() ? null : s;
    }

    private static String queryParameter(String query, String name) {
        if (query == null) return null;
        for (String pair : query.split("&")) {
            String[] parts = pair.split("=", 2);
            if (parts.length == 2 && name.equals(parts[0])) {
                return java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isHopByHopHeader(String name) {
        if (name == null) {
            return true;
        }
        return switch (name.toLowerCase(java.util.Locale.ROOT)) {
            case "connection", "content-length", "keep-alive", "proxy-authenticate",
                 "proxy-authorization", "te", "trailer", "transfer-encoding", "upgrade" -> true;
            default -> false;
        };
    }

    @Override
    public void close() {
        if (server != null) {
            server.stop(0);
        }
    }

    public record Config(
            int listenPort,
            URI upstream,
            String authorizationEndpoint,
            URI tokenEndpoint,
            String clientId,
            String clientSecret,
            URI redirectUri) {
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("MINISTACK_AUTH_PORT", "4180"));
        Config config = new Config(
            port,
            URI.create(required("MINISTACK_AUTH_UPSTREAM")),
            required("MINISTACK_OIDC_AUTHORIZATION_ENDPOINT"),
            URI.create(required("MINISTACK_OIDC_TOKEN_ENDPOINT")),
            required("MINISTACK_OIDC_CLIENT_ID"),
            required("MINISTACK_OIDC_CLIENT_SECRET"),
            URI.create(System.getenv().getOrDefault(
                "MINISTACK_OIDC_REDIRECT_URI",
                "http://localhost:" + port + "/oauth2/callback"))
        );
        MiniStackAuthProxy proxy = new MiniStackAuthProxy(config);
        System.out.println("MiniStack auth proxy listening at " + proxy.start());
        Thread.currentThread().join();
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing environment variable " + name);
        }
        return value;
    }
}
