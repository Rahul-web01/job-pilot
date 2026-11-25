package com.jobpilot.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jobpilot.profile.Profile;
import com.jobpilot.source.JobPosting;

class JobMatcherExperienceTest {

    static Profile profile(String level, String years) {
        Profile p = new Profile();
        p.setTargetTitles("software engineer\nbackend\nintern\njava");
        p.setSkills("java\nspring boot\nkafka");
        p.setLocations("india\nbengaluru");
        p.setExperienceLevel(level);
        p.setYearsExperience(years);
        return p;
    }

    static JobMatcher.Match eval(Profile p, String title, String desc) {
        return new JobMatcher().evaluate(new JobPosting("t", "1", null, "Acme", title, "Bengaluru, India", "u", desc, null), p);
    }

    @Test
    void fresherDropsSeniorRolesAndBoostsEntryLevel() {
        Profile fresher = profile("FRESHER", "0");
        String desc = "Build Java and Spring Boot services with Kafka.";
        assertThat(eval(fresher, "Senior Software Engineer", desc).keep()).isFalse();
        assertThat(eval(fresher, "Software Engineer 3", desc).keep()).isFalse();
        assertThat(eval(fresher, "Intermediate Backend Engineer, India", desc).keep()).isFalse();
        assertThat(eval(fresher, "Staff Software Engineer", desc).keep()).isFalse();
        var intern = eval(fresher, "Software Engineer, Intern", desc);
        var plain = eval(fresher, "Software Engineer", desc);
        assertThat(intern.keep()).isTrue();
        assertThat(intern.score()).isEqualTo(Math.min(100, plain.score() + 25));
        assertThat(eval(fresher, "Software Engineer I", desc).score()).isEqualTo(intern.score());
    }

    @Test
    void fresherDropsJobsAskingForThreeOrMoreYears() {
        Profile fresher = profile("FRESHER", "0");
        assertThat(eval(fresher, "Software Engineer", "You have 4+ years of experience building backends in Java.").keep()).isFalse();
        var two = eval(fresher, "Software Engineer", "2 years of experience with Java preferred.");
        var none = eval(fresher, "Software Engineer", "Strong Java fundamentals.");
        assertThat(two.keep()).isTrue();
        assertThat(two.score()).isEqualTo(none.score() - 15);
    }

    @Test
    void experiencedDropsInternshipsAndJobsFarAboveTheirYears() {
        Profile exp = profile("EXPERIENCED", "2");
        assertThat(eval(exp, "Software Engineer, Intern", "Java").keep()).isFalse();
        assertThat(eval(exp, "Backend Engineer", "Requires 8+ years of experience in Java.").keep()).isFalse();
        assertThat(eval(exp, "Senior Backend Engineer", "Java").keep()).isFalse();
        assertThat(eval(exp, "Backend Engineer", "2+ years of experience in Java.").keep()).isTrue();
        assertThat(eval(profile("EXPERIENCED", "5"), "Senior Backend Engineer", "5+ years of experience").keep()).isTrue();
    }

    @Test
    void levelWordsAloneDoNotMatchOtherFields() {
        Profile fresher = profile("FRESHER", "0");
        assertThat(eval(fresher, "Product Management Intern", "Java").keep()).isFalse();
        assertThat(eval(fresher, "Finance Intern", "Java").keep()).isFalse();
        assertThat(eval(fresher, "Software Engineer, Intern", "Java").keep()).isTrue();
        assertThat(eval(fresher, "Java Developer Intern", "Java").keep()).isTrue();
    }

    @Test
    void remoteAbroadIsNotYourLocation() {
        var want = java.util.List.of("india", "bengaluru", "remote");
        assertThat(JobMatcher.locationMatches("Remote - India", want)).isTrue();
        assertThat(JobMatcher.locationMatches("Bengaluru", want)).isTrue();
        assertThat(JobMatcher.locationMatches("Remote", want)).isTrue();
        assertThat(JobMatcher.locationMatches("Remote - USA", want)).isFalse();
        assertThat(JobMatcher.locationMatches("Remote - UK or North Macedonia", want)).isFalse();
        assertThat(JobMatcher.locationMatches("Dublin", want)).isFalse();
    }

    @Test
    void noLevelMeansNoChange() {
        Profile none = profile(null, null);
        assertThat(eval(none, "Senior Software Engineer", "Java").keep()).isTrue();
    }

    @Test
    void readsRequiredYears() {
        assertThat(JobMatcher.yearsRequired("3+ years of experience and 5 years in Go")).isEqualTo(3);
        assertThat(JobMatcher.yearsRequired("Minimum 2-4 yrs experience in backend")).isEqualTo(2);
        assertThat(JobMatcher.yearsRequired("Founded 15 years ago, we build payments.")).isNull();
        assertThat(JobMatcher.yearsRequired("A 6-month internship")).isNull();
    }
}
