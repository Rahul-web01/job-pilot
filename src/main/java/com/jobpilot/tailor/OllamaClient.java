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
import tools.jackson.databind.ObjectMapper;

/** Talks to a local Ollama server (free, runs on your computer). */
@Component
public class OllamaClient {

    private final RestClient http;
    private final ObjectMapper json = new ObjectMapper();

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
     * Asks the model for a JSON object with the given string fields.
     * Ollama's structured output keeps small models on the expected shape.
     */
    public JsonNode generateJson(Profile profile, String prompt, List<String> fields) {
        Map<String, Object> properties = new java.util.LinkedHashMap<>();
        for (String f : fields) {
            properties.put(f, Map.of("type", "string"));
        }
        Map<String, Object> body = Map.of(
                "model", profile.getOllamaModel(),
                "prompt", prompt,
                "stream", false,
                "format", Map.of("type", "object", "properties", properties, "required", fields),
                // num_predict caps the answer length so a small model can't ramble until the timeout
                "options", Map.of("temperature", 0.3, "num_ctx", 8192, "num_predict", 900));
        JsonNode res = http.post()
                .uri(profile.getOllamaUrl() + "/api/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);
        if (res == null || res.path("response").isMissingNode()) {
            throw new IllegalStateException("Empty answer from Ollama");
        }
        return json.readTree(res.path("response").asString());
    }
}
