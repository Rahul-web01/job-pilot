package com.jobpilot.search;

/** Published after every job search (manual or scheduled), so Autopilot can carry on. */
public record SearchFinishedEvent(int added) {
}
