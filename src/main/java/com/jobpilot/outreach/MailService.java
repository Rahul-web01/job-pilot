package com.jobpilot.outreach;

import java.nio.file.Path;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import com.jobpilot.profile.Profile;

import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;

/** Sends email through your own Gmail account (free, up to ~500 a day). */
@Service
public class MailService {

    private final String host;
    private final int port;
    private final boolean startTls;

    public MailService(@Value("${jobpilot.smtp.host:smtp.gmail.com}") String host,
            @Value("${jobpilot.smtp.port:587}") int port,
            @Value("${jobpilot.smtp.starttls:true}") boolean startTls) {
        this.host = host;
        this.port = port;
        this.startTls = startTls;
    }

    public record Attachment(Path path, String fileName) {
    }

    /** Sends one email and returns its Message-ID (used to thread a follow-up). */
    public String send(Profile from, String to, String toName, String subject, String body, Attachment attachment,
            String inReplyTo) throws Exception {
        JavaMailSenderImpl sender = sender(from);
        MimeMessage msg = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(msg, attachment != null, "UTF-8");
        helper.setFrom(new InternetAddress(from.getSmtpEmail(), from.getFullName().isBlank() ? null : from.getFullName(), "UTF-8"));
        helper.setTo(toName == null || toName.isBlank() ? new InternetAddress(to) : new InternetAddress(to, toName, "UTF-8"));
        helper.setSubject(subject);
        helper.setText(body, false);
        if (attachment != null) {
            helper.addAttachment(attachment.fileName(), new FileSystemResource(attachment.path()), "application/pdf");
        }
        if (inReplyTo != null) {
            msg.setHeader("In-Reply-To", inReplyTo);
            msg.setHeader("References", inReplyTo);
        }
        sender.send(msg);
        return msg.getMessageID();
    }

    /** Logs in to Gmail without sending anything, to check the address and App Password. */
    public void testLogin(Profile from) throws Exception {
        sender(from).testConnection();
    }

    private JavaMailSenderImpl sender(Profile from) {
        if (!from.canSendEmail()) {
            throw new IllegalStateException("Add your Gmail address and App Password on the Profile tab first.");
        }
        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(host);
        sender.setPort(port);
        sender.setUsername(from.getSmtpEmail());
        sender.setPassword(from.getSmtpPassword());
        Properties p = sender.getJavaMailProperties();
        p.put("mail.smtp.auth", "true");
        p.put("mail.smtp.starttls.enable", String.valueOf(startTls));
        p.put("mail.smtp.starttls.required", String.valueOf(startTls));
        p.put("mail.smtp.connectiontimeout", "10000");
        p.put("mail.smtp.timeout", "20000");
        p.put("mail.smtp.writetimeout", "20000");
        return sender;
    }
}
