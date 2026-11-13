package dev.smartcodereview.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/** Small JSON-over-HTTP helper shared by the providers (java.net.http + Jackson trees: native friendly). */
public final class JsonHttp {

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper;
    private final String host;

    /** host: name used in error messages, e.g. "GitHub". */
    public JsonHttp(ObjectMapper mapper, String host) {
        this.mapper = mapper;
        this.host = host;
    }

    public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
        try {
            return http.send(request, handler);
        } catch (IOException e) {
            throw new ProviderException(502, host + " nicht erreichbar: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException(503, "Abgebrochen");
        }
    }

    public JsonNode json(HttpRequest request, String what) {
        HttpResponse<String> res = send(request, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300) {
            String message = res.body();
            try {
                JsonNode err = mapper.readTree(res.body());
                JsonNode m = err.has("message") ? err.get("message") : err.path("error");
                message = m.isMissingNode() ? message : m.isTextual() ? m.asText() : m.toString();
            } catch (IOException ignored) {
                // keep the raw body
            }
            throw new ProviderException(res.statusCode() == 401 ? 401 : res.statusCode(),
                    host + " " + res.statusCode() + " auf " + what + ": " + message);
        }
        try {
            return mapper.readTree(res.body());
        } catch (IOException e) {
            throw new ProviderException(502, "Ungültige Antwort von " + host + ": " + e.getMessage());
        }
    }

    public HttpRequest.BodyPublisher body(JsonNode node) {
        return HttpRequest.BodyPublishers.ofString(node.toString());
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
