package com.jobpilot.tailor;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jobpilot.profile.Profile;

class PdfServiceTest {

    @Test
    void dropsResumeHeaderAndFindsSectionHeadings() {
        Profile p = new Profile();
        p.setFirstName("Test");
        p.setLastName("Candidate");
        String text = """
                Test Candidate
                test.candidate@example.com · +91 90000 00000 · Pune
                Experience
                Software Engineer, Example Tech — 2023 to Present
                Built REST APIs serving 2M requests/day
                Projects
                Expense Tracker — Spring Boot, MySQL
                Education
                B.Tech Computer Science, 2023
                """;

        var body = PdfService.body(text, p);

        assertThat(body.get(0)).isEqualTo(new PdfService.Line("Experience", true));
        assertThat(body).noneMatch(l -> l.text().contains("@") || l.text().equals("Test Candidate"));
        assertThat(body).filteredOn(PdfService.Line::heading).extracting(PdfService.Line::text)
                .containsExactly("Experience", "Projects", "Education");
        assertThat(body).anyMatch(l -> !l.heading() && l.text().startsWith("Built REST APIs"));
    }

    @Test
    void writesSkillNamesProperly() {
        assertThat(PdfService.prettySkill("java")).isEqualTo("Java");
        assertThat(PdfService.prettySkill("spring boot")).isEqualTo("Spring Boot");
        assertThat(PdfService.prettySkill("aws")).isEqualTo("AWS");
        assertThat(PdfService.prettySkill("postgresql")).isEqualTo("PostgreSQL");
    }

    @Test
    void replacesCharactersTheBuiltInFontsLack() {
        org.assertj.core.api.Assertions.assertThat(PdfService.pdfSafe("Controller → Service → Repository ✓"))
                .isEqualTo("Controller <span style=\"white-space: nowrap\">-&gt;</span> Service "
                        + "<span style=\"white-space: nowrap\">-&gt;</span> Repository +");
    }
}
