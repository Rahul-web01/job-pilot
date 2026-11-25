package com.jobpilot.apply;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.jobpilot.job.Job;
import com.jobpilot.profile.Profile;

/** Questions and options copied from real Greenhouse forms (GitLab, Stripe, Rubrik); the profile is made up. */
class QuestionAnswererTest {

    static Profile profile() {
        Profile p = new Profile();
        p.setFirstName("Asha");
        p.setLastName("Verma");
        p.setCity("Pune");
        p.setCountry("India");
        p.setNeedsSponsorship("No");
        p.setSchool("Example University");
        p.setDegree("B.Tech");
        p.setDiscipline("Computer Science and Engineering");
        p.setStartYear("2023");
        p.setGradYear("2027");
        p.setCurrentCompany("Acme");
        p.setCurrentTitle("Java Intern");
        p.setPrimaryLanguage("Java with Spring Boot");
        p.setOpenSourceLinks("https://github.com/asha/ledger");
        p.setLinkedinUrl("https://linkedin.com/in/asha");
        p.setFullTimeStart("July 2027");
        p.setInternshipAvailable("Yes");
        p.setOnsiteOk("Yes");
        p.setOfferDeadlines("No active offer deadlines.");
        p.setCustomAnswers("hackerrank profile = https://hackerrank.com/asha");
        return p;
    }

    static Job job(String company, String location) {
        Job j = new Job();
        j.setCompany(company);
        j.setLocation(location);
        return j;
    }

    private static String pick(QuestionAnswerer a, String label, String... options) {
        return FormFiller.best(List.of(options), a.answer(label));
    }

    @Test
    void gitlabQuestions() {
        var a = new QuestionAnswerer(profile(), job("GitLab", "Bangalore, India"));
        assertThat(pick(a, "Are you located in India?*", "Yes", "No")).isEqualTo("Yes");
        assertThat(pick(a, "Will you now or in the future require sponsorship for a visa to remain in your current location?*",
                "No", "Yes, Netherlands Highly Skilled Migrant Visa", "Yes, EU Blue Card")).isEqualTo("No");
        assertThat(pick(a, "Have you previously worked at or consulted for GitLab?*", "Yes", "No")).isEqualTo("No");
        assertThat(pick(a, "Are you subject to any employment agreements and/or post-employment restrictions?*", "Yes", "No"))
                .isEqualTo("No");
        assertThat(a.answer("What is your current country of residence?*").text()).isEqualTo("India");
        assertThat(a.answer("Please share links of any open source projects you own or have made contributions to*").text())
                .isEqualTo("https://github.com/asha/ledger");
        assertThat(a.answer("What is your primary programming language and/or framework?").text()).isEqualTo("Java with Spring Boot");
        assertThat(a.answer("What's the name you'd prefer us to use throughout the interview process?").text()).isEqualTo("Asha");
    }

    @Test
    void stripeQuestions() {
        var a = new QuestionAnswerer(profile(), job("Stripe", "Bengaluru"));
        assertThat(pick(a, "Do you require visa sponsorship, now or in the future, to continue working in the country where this role is based?",
                "Yes, I will require visa sponsorship now or in the future to continue working in the country where this role is based.",
                "No, I do not require visa sponsorship now or in the future to continue working in the country where this role is based."))
                .startsWith("No, I do not");
        assertThat(pick(a, "Are you currently eligible to work in India?",
                "Yes, I am currently eligible to work in the country where this role is based.",
                "No, I am not currently eligible to work in the country where this role is based.")).startsWith("Yes, I am");
        var start = a.answer("When are you available to start full-time?");
        assertThat(FormFiller.all(List.of("Q4 2026 (October - December)", "Q1 2027 (January - March)",
                "Q2 2027 (April - June)", "Q3 2027 (July - September)"), start)).containsExactly("Q3 2027 (July - September)");
        assertThat(pick(a, "Degree", "High School", "Associate's Degree", "Bachelor's Degree", "Master's Degree"))
                .isEqualTo("Bachelor's Degree");
        assertThat(pick(a, "Discipline", "Biology", "Computer Engineering", "Computer Science")).isEqualTo("Computer Science");
        assertThat(a.answer("End date year").text()).isEqualTo("2027");
        assertThat(a.answer("Start date year").text()).isEqualTo("2023");
        assertThat(a.answer("Who is your current or previous employer?").text()).isEqualTo("Acme");
        assertThat(a.answer("What is your current or previous job title?").text()).isEqualTo("Java Intern");
        assertThat(a.answer("Do you have an active offer deadline or another recruiting-process deadline we should be aware of?").text())
                .isEqualTo("No active offer deadlines.");
        assertThat(pick(a, "Do you opt-in to receive WhatsApp messages from Stripe Recruiting?", "Yes", "No")).isEqualTo("No");
    }

    @Test
    void rubrikQuestions() {
        var a = new QuestionAnswerer(profile(), job("Rubrik", "Bangalore"));
        assertThat(pick(a, "Are you currently or have you ever been employed by Rubrik or contracted to provide services to Rubrik?",
                "I have never been employed by Rubrik or contracted to provide services to Rubrik",
                "I am currently a full/part-time employee for Rubrik", "I was previously a contractor for Rubrik"))
                .startsWith("I have never");
        assertThat(pick(a, "Will you be available to take up an ONSITE internship in the month of January 2027 to May 2027?", "Yes", "No"))
                .isEqualTo("Yes");
        assertThat(a.answer("What is the name of your current employer?").text()).isEqualTo("Acme");
    }

    @Test
    void leavesAgreementsDiversityAndUnknownFactsToYou() {
        var a = new QuestionAnswerer(profile(), job("Rubrik", "Bangalore"));
        assertThat(a.answer("Please Review the NDA and indicate your agreement by typing your full name below").skip())
                .isEqualTo(QuestionAnswerer.Skip.LEGAL);
        assertThat(a.answer("I acknowledge that I have read Rubrik’s Candidate Privacy Notice").skip()).isEqualTo(QuestionAnswerer.Skip.LEGAL);
        assertThat(a.answer("Gender").skip()).isEqualTo(QuestionAnswerer.Skip.DEMOGRAPHIC);
        assertThat(a.answer("Veteran Status").skip()).isEqualTo(QuestionAnswerer.Skip.DEMOGRAPHIC);
        // known questions with an empty Profile box are never guessed (and never sent to the AI)
        assertThat(a.answer("CGPA/GPA").skip()).isEqualTo(QuestionAnswerer.Skip.NO_ANSWER);
        assertThat(a.answer("Codeforces Rating").skip()).isEqualTo(QuestionAnswerer.Skip.NO_ANSWER);
        assertThat(a.answer("Why do you want to work at Rubrik?").skip()).isEqualTo(QuestionAnswerer.Skip.UNKNOWN);
    }

    @Test
    void yourOwnAnswersComeFirst() {
        var a = new QuestionAnswerer(profile(), job("Acme", "Pune"));
        var ans = a.answer("Link to your HackerRank profile");
        assertThat(ans.text()).isEqualTo("https://hackerrank.com/asha");
        assertThat(ans.source()).isEqualTo(QuestionAnswerer.Source.YOUR_ANSWERS);
    }

    @Test
    void onlyOpenQuestionsGoToTheAi() {
        assertThat(FormFiller.openQuestion("Why do you want to work at Rubrik?")).isTrue();
        assertThat(FormFiller.openQuestion("Past Internship")).isTrue();
        assertThat(FormFiller.openQuestion("Are you willing to relocate?")).isFalse();
        assertThat(FormFiller.openQuestion("Codeforces Rating")).isFalse();
    }
}
