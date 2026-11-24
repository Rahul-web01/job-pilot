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
        assertThat(TailorService.finishLetter("Dear Hiring Team,\n\nBody", p)).endsWith("Best regards,\nTest Candidate");
        assertThat(TailorService.finishLetter("Dear Hiring Team,\n\nRegards,\nTest Candidate", p))
                .isEqualTo("Dear Hiring Team,\n\nRegards,\nTest Candidate");
    }

    @Test
    void flagsClichesInventedNumbersAndMissingCompany() {
        Profile p = new Profile();
        p.setResumeText("Built a CRUD app with 27 tests. Solved 600+ problems.");
        com.jobpilot.job.Job job = new com.jobpilot.job.Job();
        job.setCompany("Stripe");
        job.setTitle("Backend Intern");
        job.setDescription("Java services.");
        String bad = "I am excited to apply. I wrote 27 tests and served 5000 users.";
        var issues = TailorService.letterProblems(bad, job, p);
        assertThat(issues).anyMatch(i -> i.contains("excited"));
        assertThat(issues).anyMatch(i -> i.contains("5000"));
        assertThat(issues).noneMatch(i -> i.contains("number 27"));
        assertThat(issues).anyMatch(i -> i.contains("Stripe"));
        assertThat(issues).anyMatch(i -> i.contains("too short"));
    }

    @Test
    void acceptsAGoodLetter() {
        Profile p = new Profile();
        p.setResumeText("Built an Employee Management System with 27 tests. Solved 600+ DSA problems.");
        com.jobpilot.job.Job job = new com.jobpilot.job.Job();
        job.setCompany("Stripe");
        job.setTitle("Backend Intern");
        job.setDescription("Java services.");
        String good = "Dear Hiring Team,\n\n" + "I'm applying for the Backend Intern role at Stripe. ".repeat(4)
                + "I built an Employee Management System covered by 27 tests and solved 600+ DSA problems. ".repeat(8)
                + "\n\nBest regards,\nTest";
        assertThat(TailorService.letterProblems(good, job, p)).isEmpty();
    }

    @Test
    void polishSwapsFillerWordsAndAddsSignOff() {
        Profile p = new Profile();
        p.setFirstName("Test");
        p.setLastName("Candidate");
        String out = TailorService.polish("Here is the letter:\nDear Hiring Team,\n\nI utilized Kafka.  \n\n\n\nUtilizing Java.", p);
        assertThat(out).startsWith("Dear Hiring Team,").contains("I used Kafka.").contains("Using Java.")
                .doesNotContain("\n\n\n").endsWith("Best regards,\nTest Candidate");
    }
}
