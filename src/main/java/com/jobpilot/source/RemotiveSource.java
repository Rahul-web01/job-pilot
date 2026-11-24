package com.jobpilot.source;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.jobpilot.profile.Profile;

import tools.jackson.databind.JsonNode;

/** Free public API for remote jobs: https://remotive.com/api/remote-jobs (max ~4 calls/day). */
@Component
public class RemotiveSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(RemotiveSource.class);

    private final RestClient http;

    public RemotiveSource(RestClient http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "remotive";
    }

    @Override
    public List<JobPosting> fetch(Profile profile) {
        List<JobPosting> out = new ArrayList<>();
        for (String search : Profile.lines(profile.getRemotiveSearches())) {
            try {
                JsonNode root = http.get()
                        .uri("https://remotive.com/api/remote-jobs?search={q}&limit=100", search)
                        .retrieve()
                        .body(JsonNode.class);
                if (root == null) {
                    continue;
                }
                for (JsonNode j : root.path("jobs")) {
                    String location = Json.text(j, "candidate_required_location");
                    out.add(new JobPosting(
                            name(),
                            Json.text(j, "id"),
                            null,
                            Json.text(j, "company_name"),
                            Json.text(j, "title"),
                            "Remote" + (location == null || location.isBlank() ? "" : " (" + location + ")"),
                            Json.text(j, "url"),
                            Json.htmlToText(Json.text(j, "description"), false),
                            Json.parseInstant(Json.text(j, "publication_date"))));
                }
            } catch (Exception e) {
                log.warn("Remotive search '{}' failed: {}", search, e.getMessage());
            }
        }
        return out;
    }
}
