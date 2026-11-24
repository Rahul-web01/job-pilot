package com.jobpilot.source;

import java.time.Instant;

/** A job as fetched from a source, before filtering and saving. */
public record JobPosting(
        String source,
        String sourceId,
        /** Company slug on the platform (Greenhouse board / Lever company). */
        String board,
        String company,
        String title,
        String location,
        String url,
        String description,
        Instant postedAt) {

    public String externalKey() {
        return source + ":" + sourceId;
    }
}
