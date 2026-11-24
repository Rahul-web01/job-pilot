package com.jobpilot.source;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.jobpilot.profile.Profile;

import tools.jackson.databind.JsonNode;

/** Free public API: https://github.com/lever/postings-api */
@Component
public class LeverSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(LeverSource.class);

    private final RestClient http;

    public LeverSource(RestClient http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "lever";
    }

    @Override
    public List<JobPosting> fetch(Profile profile) {
        List<JobPosting> out = new ArrayList<>();
        for (String company : Profile.lines(profile.getLeverCompanies())) {
            try {
                JsonNode root = http.get()
                        .uri("https://api.lever.co/v0/postings/{company}?mode=json", company)
                        .retrieve()
                        .body(JsonNode.class);
                if (root == null) {
                    continue;
                }
                for (JsonNode j : root) {
                    long created = j.path("createdAt").asLong(0);
                    String description = Json.text(j, "descriptionPlain");
                    StringBuilder full = new StringBuilder(description == null ? "" : description);
                    for (JsonNode list : j.path("lists")) {
                        full.append("\n\n").append(Json.text(list, "text")).append("\n")
                                .append(Json.htmlToText(Json.text(list, "content"), false));
                    }
                    out.add(new JobPosting(
                            name(),
                            Json.text(j, "id"),
                            company,
                            company,
                            Json.text(j, "text"),
                            Json.text(j.path("categories"), "location"),
                            Json.text(j, "hostedUrl"),
                            full.toString().trim(),
                            created > 0 ? Instant.ofEpochMilli(created) : null));
                }
            } catch (Exception e) {
                log.warn("Lever company '{}' failed: {}", company, e.getMessage());
            }
        }
        return out;
    }
}
