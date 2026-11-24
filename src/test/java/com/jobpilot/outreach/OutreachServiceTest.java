package com.jobpilot.outreach;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.jobpilot.job.Job;
import com.jobpilot.profile.Profile;

class OutreachServiceTest {

    @Test
    void stripsGreetingAndSignOffTheModelAdds() {
        String raw = "Here is the email:\n\nHi there,\n\nI'm interested in the role.\n\nBest regards,\nTest";
        assertThat(OutreachService.cleanMiddle(raw)).isEqualTo("I'm interested in the role.");
    }

    @Test
    void composesGreetingSignatureAndOptOut() {
        Profile p = new Profile();
        p.setFirstName("Test");
        p.setLastName("Candidate");
        p.setPhone("9000000000");
        Job job = new Job();
        job.setCompany("Acme");
        job.setTitle("Java Developer");
        Contact c = new Contact();
        c.setName("Priya Sharma");

        String mail = OutreachService.compose(c, job, p, "Middle.");
        assertThat(mail).startsWith("Hi Priya,\n\nMiddle.\n\nBest regards,\nTest Candidate\n9000000000");
        assertThat(mail).contains("if you'd prefer I don't follow up");
        assertThat(OutreachService.compose(null, job, p, "x")).startsWith("Hi Acme team,");
        assertThat(OutreachService.subject(job, p)).isEqualTo("Application: Java Developer – Test Candidate");
    }
}
