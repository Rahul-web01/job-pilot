package com.jobpilot.job;

/** Pipeline stages, in board order. */
public enum JobStatus {
    FOUND("Found"),
    SHORTLISTED("Shortlisted"),
    TAILORED("Resume tailored"),
    APPLIED("Applied"),
    EMAILED("HR emailed"),
    INTERVIEW("Interview"),
    OFFER("Offer"),
    REJECTED("Rejected"),
    SKIPPED("Skipped");

    private final String label;

    JobStatus(String label) {
        this.label = label;
    }

    public String getLabel() {
        return label;
    }
}
