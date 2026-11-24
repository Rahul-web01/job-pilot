package com.jobpilot.outreach;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.JobStatus;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;
import com.jobpilot.tailor.OllamaClient;
import com.jobpilot.tailor.PdfService;

/**
 * Writes short, personal emails to HR / hiring contacts and sends them when you press Send.
 * Nothing is ever sent automatically.
 */
@Service
public class OutreachService {

    private static final Logger log = LoggerFactory.getLogger(OutreachService.class);
    public static final Duration FOLLOW_UP_AFTER = Duration.ofDays(6);

    private final OutreachEmailRepository emails;
    private final ContactRepository contacts;
    private final JobRepository jobs;
    private final ProfileService profiles;
    private final OllamaClient ollama;
    private final MailService mail;
    private final PdfService pdfs;

    public OutreachService(OutreachEmailRepository emails, ContactRepository contacts, JobRepository jobs,
            ProfileService profiles, OllamaClient ollama, MailService mail, PdfService pdfs) {
        this.emails = emails;
        this.contacts = contacts;
        this.jobs = jobs;
        this.profiles = profiles;
        this.ollama = ollama;
        this.mail = mail;
        this.pdfs = pdfs;
    }

    public long sentToday() {
        Instant startOfDay = LocalDate.now().atStartOfDay(ZoneId.systemDefault()).toInstant();
        return emails.countBySentAtAfter(startOfDay);
    }

    /** Creates a draft for a contact and lets the local AI write it in the background. */
    public OutreachEmail startDraft(long contactId) {
        Contact c = contacts.findById(contactId).orElseThrow();
        Job job = jobs.findById(c.getJobId()).orElseThrow();
        Profile p = profiles.get();
        OutreachEmail e = new OutreachEmail();
        e.setJobId(job.getId());
        e.setContactId(c.getId());
        e.setToEmail(c.getEmail());
        e.setToName(c.getName());
        e.setSubject(subject(job, p));
        e.setStatus(OutreachStatus.DRAFTING);
        e.setCreatedAt(Instant.now());
        OutreachEmail saved = emails.save(e);
        Thread.ofVirtual().name("outreach-" + saved.getId()).start(() -> write(saved.getId()));
        return saved;
    }

    private void write(long emailId) {
        OutreachEmail e = emails.findById(emailId).orElseThrow();
        Job job = jobs.findById(e.getJobId()).orElseThrow();
        Contact c = contacts.findById(e.getContactId()).orElse(null);
        Profile p = profiles.get();
        try {
            String middle = ollama.generate(p, prompt(job, c, p), 260);
            e.setBody(compose(c, job, p, cleanMiddle(middle)));
            e.setStatus(OutreachStatus.DRAFT);
            e.setError(null);
        } catch (Exception ex) {
            log.warn("Drafting email {} failed", emailId, ex);
            // Fall back to a plain template so you can still edit and send
            e.setBody(compose(c, job, p, templateMiddle(job, p)));
            e.setStatus(OutreachStatus.DRAFT);
            e.setError("The local AI didn't answer, so this is a simple template. Edit it before sending.");
        }
        emails.save(e);
    }

    /** A short, polite follow-up in the same email thread. No AI needed. */
    public OutreachEmail draftFollowUp(long emailId) {
        OutreachEmail original = emails.findById(emailId).orElseThrow();
        Job job = jobs.findById(original.getJobId()).orElseThrow();
        Profile p = profiles.get();
        OutreachEmail f = new OutreachEmail();
        f.setJobId(original.getJobId());
        f.setContactId(original.getContactId());
        f.setToEmail(original.getToEmail());
        f.setToName(original.getToName());
        f.setFollowUpOfId(original.getId());
        f.setSubject(original.getSubject().startsWith("Re: ") ? original.getSubject() : "Re: " + original.getSubject());
        f.setAttachResume(false);
        f.setBody(greeting(original.getToName(), job) + "\n\n"
                + "Just following up on my note last week about the " + job.getTitle() + " role. "
                + "I'm still very interested and would be glad to share anything else that helps. "
                + "If someone else handles this hiring, I'd be grateful if you could point me to them.\n\n"
                + signature(p));
        f.setStatus(OutreachStatus.DRAFT);
        f.setCreatedAt(Instant.now());
        return emails.save(f);
    }

    public void saveDraft(long emailId, String subject, String body, boolean attachResume) {
        OutreachEmail e = emails.findById(emailId).orElseThrow();
        if (e.getStatus() == OutreachStatus.SENT || e.getStatus() == OutreachStatus.REPLIED) {
            return;
        }
        e.setSubject(subject.strip());
        e.setBody(body.replace("\r", "").strip());
        e.setAttachResume(attachResume);
        emails.save(e);
    }

    /** Sends one draft. Throws with a message for the page if it can't. */
    public void send(long emailId) {
        OutreachEmail e = emails.findById(emailId).orElseThrow();
        if (e.getStatus() != OutreachStatus.DRAFT && e.getStatus() != OutreachStatus.FAILED) {
            throw new IllegalStateException("This email was already sent.");
        }
        Profile p = profiles.get();
        if (!p.canSendEmail()) {
            throw new IllegalStateException("Add your Gmail address and App Password on the Profile tab first.");
        }
        if (sentToday() >= p.getDailySendLimitOrDefault()) {
            int limit = p.getDailySendLimitOrDefault();
            throw new IllegalStateException("You've reached today's limit of " + limit + (limit == 1 ? " email" : " emails")
                    + ". Sending lots of cold email in a day gets Gmail accounts flagged, so try again tomorrow.");
        }
        Job job = jobs.findById(e.getJobId()).orElseThrow();
        String inReplyTo = null;
        if (e.getFollowUpOfId() != null) {
            inReplyTo = emails.findById(e.getFollowUpOfId()).map(OutreachEmail::getMessageId).orElse(null);
        }
        try {
            String messageId = mail.send(p, e.getToEmail(), e.getToName(), e.getSubject(), e.getBody(),
                    e.isAttachResume() ? resumeAttachment(job, p) : null, inReplyTo);
            e.setMessageId(messageId);
            e.setSentAt(Instant.now());
            e.setStatus(OutreachStatus.SENT);
            e.setError(null);
            if (job.getStatus().ordinal() < JobStatus.EMAILED.ordinal()) {
                job.setStatus(JobStatus.EMAILED);
                job.setStatusChangedAt(Instant.now());
                jobs.save(job);
            }
        } catch (Exception ex) {
            e.setStatus(OutreachStatus.FAILED);
            e.setError(friendly(ex));
        }
        emails.save(e);
        if (e.getStatus() == OutreachStatus.FAILED) {
            throw new IllegalStateException(e.getError());
        }
    }

    public void markReplied(long emailId) {
        OutreachEmail e = emails.findById(emailId).orElseThrow();
        e.setStatus(OutreachStatus.REPLIED);
        emails.save(e);
    }

    public void delete(long emailId) {
        emails.findById(emailId).ifPresent(e -> {
            if (e.getStatus() != OutreachStatus.SENT && e.getStatus() != OutreachStatus.REPLIED) {
                emails.delete(e);
            }
        });
    }

    /** Sent emails with no reply and no follow-up yet, older than 6 days. */
    public boolean followUpDue(OutreachEmail e) {
        return e.getStatus() == OutreachStatus.SENT && !e.isFollowUp() && e.getSentAt() != null
                && e.getSentAt().isBefore(Instant.now().minus(FOLLOW_UP_AFTER))
                && !emails.existsByFollowUpOfId(e.getId());
    }

    private Path resume(Job job, Profile p) {
        Path tailored = pdfs.file(job, PdfService.RESUME);
        return tailored != null ? tailored : profiles.originalResumePdf();
    }

    private MailService.Attachment resumeAttachment(Job job, Profile p) {
        Path path = resume(job, p);
        return path == null ? null : new MailService.Attachment(path, PdfService.niceName(p, job, "Resume"));
    }

    static String subject(Job job, Profile p) {
        String who = p.getFullName().isBlank() ? "" : " – " + p.getFullName();
        return "Application: " + job.getTitle() + who;
    }

    static String greeting(String toName, Job job) {
        if (toName != null && !toName.isBlank()) {
            return "Hi " + toName.strip().split("\\s+")[0] + ",";
        }
        return "Hi " + job.getCompany() + " team,";
    }

    /** Greeting + AI-written middle + signature + a polite opt-out line. */
    static String compose(Contact c, Job job, Profile p, String middle) {
        return greeting(c == null ? null : c.getName(), job) + "\n\n" + middle.strip() + "\n\n" + signature(p)
                + "\n\nP.S. If you're not the right person for this, I'd be grateful if you could point me to them. "
                + "And if you'd prefer I don't follow up, just say so.";
    }

    static String signature(Profile p) {
        StringBuilder s = new StringBuilder("Best regards,\n").append(p.getFullName());
        if (p.getPhone() != null && !p.getPhone().isBlank()) {
            s.append("\n").append(p.getPhone());
        }
        if (p.getLinkedinUrl() != null && !p.getLinkedinUrl().isBlank()) {
            s.append("\n").append(p.getLinkedinUrl());
        }
        return s.toString();
    }

    private static String prompt(Job job, Contact c, Profile p) {
        String to = c != null && c.getRole() != null ? "a " + c.getRole() : "the hiring team";
        return """
                JOB: %s at %s (%s)
                JOB DESCRIPTION:
                %s

                CANDIDATE: %s
                CANDIDATE RESUME:
                %s

                Use ONLY facts from the resume. Never invent employers, titles, years, numbers or skills.
                Reply with the text only: no greeting, no sign-off, no subject line, no notes.

                Write the middle of a short cold email from the candidate to %s at %s, in 2 short paragraphs,
                70-110 words in total. Paragraph 1: they are interested in (or have applied for) this role, in one or
                two sentences. Paragraph 2: one or two specific results from the resume that match the job, then ask
                politely whether they could consider the attached resume or share it with the hiring manager.
                Plain, warm, confident English. No flattery, no clichés.
                """.formatted(job.getTitle(), job.getCompany(), job.getLocation(),
                cut(job.getDescription(), 4000), p.getFullName(), cut(p.getResumeText(), 6000), to, job.getCompany());
    }

    static String cleanMiddle(String s) {
        String out = s.replace("\r", "").strip();
        out = out.replaceFirst("(?is)^\\s*(here(?:'s| is)[^\\n]*?:)\\s*", "");
        // drop a greeting or sign-off the model added anyway
        out = out.replaceFirst("(?i)^(hi|hello|dear)[^\\n]*\\n+", "");
        out = out.replaceFirst("(?is)\\n+(best|kind|warm)?\\s*regards.*$", "");
        out = out.replaceFirst("(?is)\\n+(thanks|thank you|sincerely|cheers)[,!.]?\\s*\\n.*$", "");
        return out.strip();
    }

    private static String templateMiddle(Job job, Profile p) {
        return "I'm interested in the " + job.getTitle() + " role at " + job.getCompany()
                + " and wanted to reach out directly.\n\nI've attached my resume. Would you be open to considering it, "
                + "or passing it on to the hiring manager?";
    }

    private static String friendly(Exception ex) {
        String m = String.valueOf(ex.getMessage()).toLowerCase(Locale.ROOT);
        if (m.contains("535") || m.contains("authentication") || m.contains("username and password")) {
            return "Gmail rejected the login. Use a Gmail App Password (not your normal password) and check the address.";
        }
        if (m.contains("connect") || m.contains("timed out") || m.contains("unknownhost")) {
            return "Couldn't reach Gmail. Check your internet connection and try again.";
        }
        if (m.contains("invalid address") || m.contains("addressexception")) {
            return "That email address isn't valid.";
        }
        return "Sending failed: " + ex.getMessage();
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n[...]";
    }

    public List<OutreachEmail> forJob(long jobId) {
        return emails.findByJobIdOrderByIdAsc(jobId);
    }
}
