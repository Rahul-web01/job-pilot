package com.jobpilot.outreach;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import com.jobpilot.profile.Profile;

import jakarta.mail.Multipart;
import jakarta.mail.internet.MimeMessage;

/** Sends through a fake local mail server: nothing leaves this computer. */
class MailServiceTest {

    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP)
            .withConfiguration(com.icegreen.greenmail.configuration.GreenMailConfiguration.aConfig()
                    .withUser("test.candidate@example.com", "app-password"));

    @Test
    void sendsWithResumeAttachedAndThreadsFollowUp(@TempDir Path tmp) throws Exception {
        Path pdf = Files.write(tmp.resolve("resume.pdf"), "%PDF-1.4 test".getBytes());
        Profile p = new Profile();
        p.setFirstName("Test");
        p.setLastName("Candidate");
        p.setSmtpEmail("test.candidate@example.com");
        p.setSmtpPassword("app-password");
        MailService mail = new MailService("localhost", ServerSetupTest.SMTP.getPort(), false);

        String id = mail.send(p, "careers@acme.test", "Priya", "Application: Java Developer – Test Candidate",
                "Hi Priya,\n\nBody", new MailService.Attachment(pdf, "Test-Candidate-Resume-Acme.pdf"), null);
        mail.send(p, "careers@acme.test", "Priya", "Re: Application: Java Developer – Test Candidate",
                "Following up", null, id);

        MimeMessage[] got = greenMail.getReceivedMessages();
        assertThat(got).hasSize(2);
        assertThat(got[0].getFrom()[0].toString()).contains("Test Candidate").contains("test.candidate@example.com");
        assertThat(got[0].getSubject()).isEqualTo("Application: Java Developer – Test Candidate");
        Multipart parts = (Multipart) got[0].getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        assertThat(parts.getBodyPart(1).getFileName()).isEqualTo("Test-Candidate-Resume-Acme.pdf");
        assertThat(GreenMailUtil.getBody(got[0])).contains("Hi Priya");
        assertThat(got[1].getHeader("In-Reply-To")[0]).isEqualTo(id);
    }

    @Test
    void wrongPasswordFails() {
        Profile p = new Profile();
        p.setSmtpEmail("test.candidate@example.com");
        p.setSmtpPassword("wrong");
        MailService mail = new MailService("localhost", ServerSetupTest.SMTP.getPort(), false);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> mail.testLogin(p)).isInstanceOf(Exception.class);
    }
}
