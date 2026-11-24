package com.jobpilot.tailor;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.jobpilot.profile.Profile;

import tools.jackson.databind.JsonNode;

/** Talks to a local Ollama server (free, runs on your computer). */
@Component
public class OllamaClient {

    private final RestClient http;

    public OllamaClient() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        // Local models on a laptop CPU can take a few minutes
        factory.setReadTimeout(Duration.ofMinutes(6));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    /** Lists installed model names, or throws if Ollama is not running. */
    public List<String> models(Profile profile) {
        JsonNode root = http.get().uri(profile.getOllamaUrl() + "/api/tags").retrieve().body(JsonNode.class);
        return root == null ? List.of()
                : root.path("models").valueStream().map(m -> m.path("name").asString()).toList();
    }

    /**
     * Plain-text answer, capped at maxTokens.
     * (Small models tend to loop when forced into JSON, so we ask for plain text, one thing per call,
     * with a repetition penalty.)
     */
    public String generate(Profile profile, String prompt, int maxTokens) {
        Map<String, Object> body = Map.of(
                "model", profile.getOllamaModel(),
                "prompt", prompt,
                "stream", false,
                "options", Map.of(
                        "temperature", 0.4,
                        "num_ctx", 8192,
                        "num_predict", maxTokens,
                        "repeat_penalty", 1.15));
        JsonNode res = http.post()
                .uri(profile.getOllamaUrl() + "/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (res == null || res.path("response").isMissingNode() || res.path("response").asString().isBlank()) {
            throw new IllegalStateException("Empty answer from Ollama");
        }
        return res.path("response").asString();
    }
}
