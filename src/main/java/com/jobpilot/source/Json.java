package com.jobpilot.source;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

import org.jsoup.Jsoup;
import org.jsoup.parser.Parser;

import tools.jackson.databind.JsonNode;

/** Small helpers for reading job JSON safely. */
final class Json {

    private Json() {
    }

    static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return (v.isMissingNode() || v.isNull()) ? null : v.asString();
    }

    /** Turns HTML (optionally entity-escaped, like Greenhouse) into readable plain text. */
    static String htmlToText(String html, boolean escaped) {
        if (html == null) {
            return null;
        }
        String raw = escaped ? Parser.unescapeEntities(html, false) : html;
        var doc = Jsoup.parse(raw);
        doc.select("br").append("\\n");
        doc.select("p, li, div, h1, h2, h3, h4").prepend("\\n");
        return doc.text().replace("\\n", "\n").replaceAll("\n\\s*\n+", "\n\n").trim();
    }

    static Instant parseInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (Exception e) {
            try {
                return LocalDateTime.parse(value).toInstant(ZoneOffset.UTC);
            } catch (Exception ignored) {
                return null;
            }
        }
    }
}
