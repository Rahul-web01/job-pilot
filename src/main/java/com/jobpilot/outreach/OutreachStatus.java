package com.jobpilot.outreach;

public enum OutreachStatus {
    DRAFTING("Writing…"),
    DRAFT("Draft"),
    SENT("Sent"),
    FAILED("Failed"),
    REPLIED("Replied");

    private final String label;

    OutreachStatus(String label) {
        this.label = label;
    }

    public String getLabel() { return label; }
}
