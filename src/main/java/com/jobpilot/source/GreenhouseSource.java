package com.jobpilot.source;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.jobpilot.profile.Profile;

import tools.jackson.databind.JsonNode;

/** Free public API: https://developers.greenhouse.io/job-board.html */
@Component
public class GreenhouseSource implements JobSource {

    private static final Logger log = LoggerFactory.getLogger(GreenhouseSource.class);

    private final RestClient http;

    public GreenhouseSource(RestClient http) {
        this.http = http;
    }

    @Override
    public String name() {
        return "greenhouse";
    }

    @Override
    public List<JobPosting> fetch(Profile profile) {
        List<JobPosting> out = new ArrayList<>();
        for (String board : Profile.lines(profile.getGreenhouseBoards())) {
            try {
                JsonNode root = http.get()
                        .uri("https://boards-api.greenhouse.io/v1/boards/{board}/jobs?content=true", board)
                        .retrieve()
                        .body(JsonNode.class);
                if (root == null) {
                    continue;
                }
                for (JsonNode j : root.path("jobs")) {
                    String company = Json.text(j, "company_name");
                    out.add(new JobPosting(
                            name(),
                            Json.text(j, "id"),
                            board,
                            company != null ? company : board,
                            Json.text(j, "title"),
                            Json.text(j.path("location"), "name"),
                            Json.text(j, "absolute_url"),
                            Json.htmlToText(Json.text(j, "content"), true),
                            Json.parseInstant(Json.text(j, "first_published"))));
                }
            } catch (Exception e) {
                log.warn("Greenhouse board '{}' failed: {}", board, e.getMessage());
            }
        }
        return out;
    }
}
