package com.jobpilot.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Values from application.yml. The profile and sources here are only the starting
 * defaults for a brand-new install; after that everything is edited on the Profile tab.
 */
@ConfigurationProperties(prefix = "jobpilot")
public record JobPilotProperties(String dataDir, Profile profile, Sources sources, Ollama ollama) {

    public record Profile(
            List<String> targetTitles,
            List<String> skills,
            List<String> excludeTitleWords,
            List<String> locations) {
    }

    public record Sources(
            List<String> greenhouseBoards,
            List<String> leverCompanies,
            List<String> remotiveSearches) {
    }

    public record Ollama(String url, String model) {
    }
}
