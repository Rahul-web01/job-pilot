package com.jobpilot.outreach;

/** Where an email address came from, which tells you how much to trust it. */
public enum ContactSource {
    JOB_POST("In the job post", 90),
    WEBSITE("On the company website", 75),
    MANUAL("Added by you", 100),
    GUESSED("Guessed, may bounce", 20);

    private final String label;
    private final int confidence;

    ContactSource(String label, int confidence) {
        this.label = label;
        this.confidence = confidence;
    }

    public String getLabel() { return label; }
    public int getConfidence() { return confidence; }
}
