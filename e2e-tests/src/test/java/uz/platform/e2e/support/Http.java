package uz.platform.e2e.support;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublisher;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse.BodyHandlers;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * A deliberately plain HTTP client: no redirects followed, no cookies kept.
 *
 * <p>Every redirect and every cookie in these tests is handled in the open, so a
 * test can assert on the hop itself, which is usually the point.</p>
 */
public final class Http {

    public static final ObjectMapper JSON = new ObjectMapper();

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private Http() {
    }

    public static Request get(String url) {
        return new Request("GET", url);
    }

    public static Request post(String url) {
        return new Request("POST", url);
    }

    public static Request put(String url) {
        return new Request("PUT", url);
    }

    public static Request delete(String url) {
        return new Request("DELETE", url);
    }

    public static Request options(String url) {
        return new Request("OPTIONS", url);
    }

    public static String formEncode(Map<String, String> fields) {
        return fields.entrySet().stream()
                .map(field -> URLEncoder.encode(field.getKey(), UTF_8) + "=" + URLEncoder.encode(field.getValue(), UTF_8))
                .collect(Collectors.joining("&"));
    }

    /** The strings in a JSON array, or the single string, or nothing: Keycloak uses all three for "aud". */
    public static List<String> texts(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) {
            return values;
        }
        if (node.isArray()) {
            node.forEach(item -> values.add(item.asText()));
        } else {
            values.add(node.asText());
        }
        return values;
    }

    public static final class Request {

        private final String method;
        private final HttpRequest.Builder builder;
        private BodyPublisher body = BodyPublishers.noBody();

        private Request(String method, String url) {
            this.method = method;
            this.builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30));
        }

        public Request header(String name, String value) {
            builder.header(name, value);
            return this;
        }

        public Request bearer(String token) {
            return header("Authorization", "Bearer " + token);
        }

        public Request form(Map<String, String> fields) {
            header("Content-Type", "application/x-www-form-urlencoded");
            body = BodyPublishers.ofString(formEncode(fields));
            return this;
        }

        public Request json(Object value) {
            header("Content-Type", "application/json");
            try {
                body = BodyPublishers.ofString(value instanceof String text ? text : JSON.writeValueAsString(value));
            } catch (JsonProcessingException e) {
                throw new IllegalArgumentException(e);
            }
            return this;
        }

        public Response send() {
            try {
                var response = CLIENT.send(builder.method(method, body).build(), BodyHandlers.ofString());
                return new Response(response.statusCode(), response.body(), response.headers());
            } catch (IOException e) {
                throw new UncheckedIOException(method + " " + builder.build().uri() + " failed: " + e, e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
    }

    public record Response(int status, String body, HttpHeaders headers) {

        public JsonNode json() {
            try {
                return JSON.readTree(body);
            } catch (IOException e) {
                throw new AssertionError("expected JSON, got: " + this, e);
            }
        }

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        public String location() {
            return header("Location");
        }

        public boolean isRedirect() {
            return status >= 300 && status < 400;
        }

        @Override
        public String toString() {
            String shown = body == null ? "" : body.length() > 300 ? body.substring(0, 300) + "..." : body;
            return status + " " + shown;
        }
    }
}
