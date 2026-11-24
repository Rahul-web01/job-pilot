package com.jobpilot.outreach;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.api.Test;

class EmailFinderTest {

    @Test
    void extractsFromTextMailtoAndCloudflare() {
        String html = """
                <p>Send your CV to Careers@Acme.com.</p>
                <a href="mailto:talent.india@acme.com?subject=Hi">Email us</a>
                <a class="__cf_email__" data-cfemail="%s">[email protected]</a>
                <img src="logo@2x.png"> noreply@acme.com privacy@acme.com
                """.formatted(cloudflare("hr@acme.com", 0x42));
        assertThat(EmailFinderService.extractFromHtml(html))
                .containsExactlyInAnyOrder("careers@acme.com", "talent.india@acme.com", "hr@acme.com");
    }

    @Test
    void ranksHrInboxesAboveGeneralOnes() {
        int hr = EmailFinderService.rank("careers@acme.com", ContactSource.WEBSITE);
        int person = EmailFinderService.rank("priya.sharma@acme.com", ContactSource.WEBSITE);
        int info = EmailFinderService.rank("info@acme.com", ContactSource.WEBSITE);
        assertThat(hr).isGreaterThan(person);
        assertThat(person).isGreaterThan(info);
    }

    @Test
    void respectsRobotsDisallow() {
        Set<String> disallowed = Set.of("/careers", "/private*");
        assertThat(EmailFinderService.isDisallowed("/careers", disallowed)).isTrue();
        assertThat(EmailFinderService.isDisallowed("/contact", disallowed)).isFalse();
        assertThat(EmailFinderService.isDisallowed("/anything", Set.of("/"))).isTrue();
    }

    @Test
    void findsRegistrableDomain() {
        assertThat(Domains.normalize("https://www.stripe.com/jobs/123")).isEqualTo("stripe.com");
        assertThat(Domains.normalize("careers.tata.co.in")).isEqualTo("tata.co.in");
        assertThat(Domains.isJobBoard(Domains.normalize("https://job-boards.greenhouse.io/groww/jobs/1"))).isTrue();
    }

    private static String cloudflare(String email, int key) {
        StringBuilder sb = new StringBuilder(String.format("%02x", key));
        for (char c : email.toCharArray()) {
            sb.append(String.format("%02x", c ^ key));
        }
        return sb.toString();
    }
}
