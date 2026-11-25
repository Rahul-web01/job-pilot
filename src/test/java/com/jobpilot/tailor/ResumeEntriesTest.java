package com.jobpilot.tailor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobpilot.profile.Profile;

class ResumeEntriesTest {

    /** Shaped like text PDFBox extracts from a real resume: spaced headings, wrapped lines, no bullet marks. */
    static final String RESUME = """
            A S H A  V E R M A
            Backend Developer | Java | Spring Boot
            S U M M A RY
            Final-year B.Tech CSE student with 2 internships and 4 projects. Solved 500+ problems.
            E X P E R I E N C E
            Backend Intern, Acme  –  Billing Service Jul 2026 – Aug 2026
            Live: billing.example.app | GitHub: github.com/asha/billing
            Built a billing service in Java 17 and Spring Boot with PostgreSQL, handling 3 payment
            providers.
            Wrote 40 tests with JUnit 5 and server-
            side validation.
            Java Intern, Beta Corp May 2026 – Jun 2026
            Built a console inventory app in Java with 5 modules.
            P R OJ EC T S
            Chat App | React, Node Jan 2026
            Built a chat app with React and WebSockets for 20 users.
            Kafka Ledger – Event Pipeline | Java, Kafka Jun 2026
            Consumed payment events from Apache Kafka and stored them with JPA.
            E D U C AT I O N
            B.Tech, CSE 2023 – 2027
            """;

    @Test
    void splitsRolesAndProjectsAndJoinsWrappedLines() {
        List<ResumeEntries.Entry> entries = ResumeEntries.parse(RESUME);
        assertThat(entries).hasSize(4);
        assertThat(entries.get(0).experience()).isTrue();
        assertThat(entries.get(0).body()).isEqualTo(
                "Built a billing service in Java 17 and Spring Boot with PostgreSQL, handling 3 payment providers.\n"
                        + "Wrote 40 tests with JUnit 5 and server-side validation.");
        assertThat(entries.get(2).experience()).isFalse();
        assertThat(entries).noneMatch(e -> e.body().contains("github.com"));
    }

    /** Dates on their own line under each title, as many resume templates do. */
    static final String DATES_BELOW = """
            EXPERIENCE
            Full Stack Intern, Acme – Billing Service
            Jul 2026 – Aug 2026
            Live: billing.example.app | GitHub: github.com/asha/billing
            • Built a billing service in Java 17 and Spring Boot with 7 endpoints.
            • Wrote 40 tests with JUnit 5.
            Java Intern, Beta Corp
            May 2026 – Jun 2026
            • Built a console inventory app in Java with 5 modules.
            PROJECTS
            Kafka Ledger – Event Pipeline | JPMorgan Chase Job Simulation (Forage)
            Jun 2026
            GitHub: github.com/asha/ledger | Java 17, Apache Kafka
            • Consumed payment events from Apache Kafka and stored them with JPA.
            EDUCATION
            B.Tech, CSE
            2023 – 2027 (Expected)
            """;

    @Test
    void joinsTitleWithDatesOnTheNextLine() {
        List<ResumeEntries.Entry> entries = ResumeEntries.parse(DATES_BELOW);
        assertThat(entries).extracting(ResumeEntries.Entry::name).containsExactly(
                "Full Stack Intern, Acme – Billing Service", "Java Intern, Beta Corp",
                "Kafka Ledger – Event Pipeline");
        // the next role's title must not leak into the previous role's details
        assertThat(entries.get(0).body()).doesNotContain("Beta Corp");
        assertThat(TailorService.plainParagraph(entries.get(0))).startsWith("As a Full Stack Intern at Acme, I built");
        assertThat(TailorService.plainParagraph(entries.get(2))).startsWith("For Kafka Ledger, I consumed");
    }

    @Test
    void recognisesDateOnlyLines() {
        assertThat(ResumeEntries.DATES_ONLY.matcher("Jul 2026 – Aug 2026").matches()).isTrue();
        assertThat(ResumeEntries.DATES_ONLY.matcher("Jun 2026").matches()).isTrue();
        assertThat(ResumeEntries.DATES_ONLY.matcher("2025 - Present").matches()).isTrue();
        assertThat(ResumeEntries.DATES_ONLY.matcher("Built 7 APIs in 2026").matches()).isFalse();
    }

    @Test
    void picksBestRoleThenBestOtherEntry() {
        String job = "Payments backend engineer. You will build payment services with Java, Kafka and PostgreSQL.";
        List<ResumeEntries.Entry> picked = ResumeEntries.pick(ResumeEntries.parse(RESUME), job);
        assertThat(picked).extracting(ResumeEntries.Entry::title)
                .containsExactly("Backend Intern, Acme  –  Billing Service Jul 2026 – Aug 2026",
                        "Kafka Ledger – Event Pipeline | Java, Kafka Jun 2026");
    }

    @Test
    void plainParagraphReadsAsSentences() {
        List<ResumeEntries.Entry> entries = ResumeEntries.parse(RESUME);
        assertThat(TailorService.plainParagraph(entries.get(0))).startsWith(
                "As a Backend Intern at Acme, I built a billing service in Java 17").contains(" I also wrote 40 tests");
        assertThat(TailorService.plainParagraph(entries.get(3))).startsWith("For Kafka Ledger, I consumed payment events");
    }

    @Test
    void whoLineComesFromSummary() {
        assertThat(TailorService.whoLine(RESUME))
                .isEqualTo("I'm a final-year B.Tech CSE student with 2 internships and 4 projects.");
    }

    @Test
    void catchesFactsTheEntryDoesNotHave() {
        ResumeEntries.Entry billing = ResumeEntries.parse(RESUME).get(0);
        Profile p = new Profile();
        String faithful = "As a Backend Intern at Acme, I built a billing service in Java 17 and Spring Boot with PostgreSQL"
                + " for 3 payment providers, and wrote 40 tests with JUnit 5.";
        assertThat(TailorService.paragraphProblems(faithful, billing, p)).isEmpty();
        String invented = "At Acme I built a billing service with Kafka microservices on blockchain that served 9000 users"
                + " and I am excited about it.";
        assertThat(TailorService.paragraphProblems(invented, billing, p))
                .contains("number 9000", "technology kafka", "technology microservices", "technology blockchain",
                        "phrase excited");
    }

    @Test
    void techMatchesOutweighGenericWords() {
        String job = "Money movement team: transaction processing with Kafka for payments, orders and customers.";
        List<ResumeEntries.Entry> picked = ResumeEntries.pick(ResumeEntries.parse(RESUME), job);
        assertThat(picked.get(1).title()).startsWith("Kafka Ledger");
    }

    @Test
    void dateRangesAreRemovedFromParagraphs() {
        String para = "I built an app at Beta Corp May 2026 – Jun 2026. I shipped it (Jul 2026 - Aug 2026).";
        assertThat(para.replaceAll(TailorService.DATE_RANGE, "").replaceAll("\\s+([.,])", "$1"))
                .isEqualTo("I built an app at Beta Corp. I shipped it.");
    }

    @Test
    void nonBreakingSpacesAroundDashesAreHandled() {
        String resume = "EXPERIENCE\nJava Intern, Acme\u00A0\u00A0\u2013\u00A0\u00A0Billing App Jul 2026\nBuilt a billing app.\n";
        ResumeEntries.Entry e = ResumeEntries.parse(resume).get(0);
        assertThat(TailorService.lead(e)).isEqualTo("As a Java Intern at Acme, I ");
    }
}
