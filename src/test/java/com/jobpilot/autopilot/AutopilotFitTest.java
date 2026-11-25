package com.jobpilot.autopilot;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jobpilot.job.Job;
import com.jobpilot.profile.Profile;

class AutopilotFitTest {

    static Job job(int score, String location) {
        Job j = new Job();
        j.setScore(score);
        j.setLocation(location);
        return j;
    }

    @Test
    void onlyJobsAboveTheMinimumInYourPlaces() {
        Profile p = new Profile();
        p.setLocations("india\nbengaluru\nremote");
        p.setAutoMinScore(70);
        assertThat(AutopilotService.fits(job(80, "Bengaluru, India"), p)).isTrue();
        assertThat(AutopilotService.fits(job(80, "Remote - India"), p)).isTrue();
        assertThat(AutopilotService.fits(job(65, "Bengaluru, India"), p)).isFalse();
        assertThat(AutopilotService.fits(job(0, "Gurugram, India"), p)).isFalse();   // re-scored as not fitting
        assertThat(AutopilotService.fits(job(73, "Remote - USA"), p)).isFalse();
        assertThat(AutopilotService.fits(job(74, "Dublin"), p)).isFalse();
    }
}
