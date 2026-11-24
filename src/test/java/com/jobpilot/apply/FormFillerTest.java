package com.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.jobpilot.profile.Profile;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

/** Fills a local copy of a Greenhouse-style form (nothing is sent anywhere). */
class FormFillerTest {

    @Test
    void fillsKnownFieldsUploadsFilesAndNeverSubmits(@TempDir Path tmp) throws Exception {
        Assumptions.assumeTrue(ApplyService.chromeInstalled(), "Google Chrome is needed for this test");
        Path resume = Files.write(tmp.resolve("resume.pdf"), "%PDF-1.4 test".getBytes());
        Path cover = Files.write(tmp.resolve("cover.pdf"), "%PDF-1.4 test".getBytes());

        Profile p = new Profile();
        p.setFirstName("Test");
        p.setLastName("User");
        p.setEmail("test.user@example.com");
        p.setPhone("9999999999");
        p.setLinkedinUrl("https://linkedin.com/in/test-user");
        p.setNoticePeriod("30 days");

        try (Playwright pw = Playwright.create(new Playwright.CreateOptions()
                .setEnv(java.util.Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))) {
            var browser = pw.chromium().launch(new BrowserType.LaunchOptions().setChannel("chrome").setHeadless(true));
            Page page = browser.newPage();
            page.navigate(getClass().getResource("/forms/greenhouse-like.html").toString());

            var result = new FormFiller(page, p).fill(
                    new FormFiller.Upload(resume, "Test-User-Resume-Acme.pdf"),
                    new FormFiller.Upload(cover, "Test-User-Cover-Letter-Acme.pdf"),
                    "Dear Hiring Team, ...");

            assertThat(page.inputValue("#first_name")).isEqualTo("Test");
            assertThat(page.inputValue("#last_name")).isEqualTo("User");
            assertThat(page.inputValue("#email")).isEqualTo("test.user@example.com");
            assertThat(page.inputValue("#phone")).isEqualTo("9999999999");
            assertThat(page.inputValue("#q1")).isEqualTo("https://linkedin.com/in/test-user");
            assertThat(page.inputValue("#q2")).isEqualTo("30 days");
            assertThat(page.evaluate("document.getElementById('resume').files[0].name")).isEqualTo("Test-User-Resume-Acme.pdf");
            assertThat(page.evaluate("document.getElementById('cover_letter').files[0].name")).isEqualTo("Test-User-Cover-Letter-Acme.pdf");

            // Custom questions are left for the user and reported
            assertThat(result.needsYou()).anyMatch(s -> s.startsWith("Why do you want to work here"));
            assertThat(result.needsYou()).anyMatch(s -> s.startsWith("Are you legally authorized"));
            // It must never submit
            assertThat(page.evaluate("window.submitted === true")).isEqualTo(false);
            System.out.println("Filled: " + result.filled() + " | Needs you: " + result.needsYou());
        }
    }
}
