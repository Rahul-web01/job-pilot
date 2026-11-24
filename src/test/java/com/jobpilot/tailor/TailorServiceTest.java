package com.jobpilot.tailor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jobpilot.profile.Profile;

class TailorServiceTest {

    @Test
    void removesChattyPreamble() {
        assertThat(TailorService.stripPreamble("Here is a cover letter:\n\nDear Hiring Team,\nHi"))
                .isEqualTo("Dear Hiring Team,\nHi");
        assertThat(TailorService.stripPreamble("\"Backend developer with Java.\"")).isEqualTo("Backend developer with Java.");
    }

    @Test
    void capsSummaryAtSentenceLimit() {
        String rambling = "One. Two. Three. Four. Five. Five again. Five again.";
        assertThat(TailorService.firstSentences(rambling, 4)).isEqualTo("One. Two. Three. Four.");
    }

    @Test
    void addsSignOffWhenMissing() {
        Profile p = new Profile();
        p.setFirstName("Test");
        p.setLastName("Candidate");
        assertThat(TailorService.finishLetter("Dear Hiring Team,\n\nBody", p)).endsWith("Regards,\nTest Candidate");
        assertThat(TailorService.finishLetter("Dear Hiring Team,\n\nRegards,\nTest Candidate", p))
                .isEqualTo("Dear Hiring Team,\n\nRegards,\nTest Candidate");
    }
}
