package com.jobpilot.tailor;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.JobStatus;
import com.jobpilot.job.TaskState;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;

import tools.jackson.databind.JsonNode;

/**
 * Writes a tailored summary and cover letter for a job with the local AI, then builds the PDFs.
 * Only the summary is rewritten; your experience, projects and education are copied
 * from your uploaded resume as-is so the AI cannot invent anything there.
 */
@Service
public class TailorService {

    private static final Logger log = LoggerFactory.getLogger(TailorService.class);

    private final JobRepository jobs;
    private final ProfileService profiles;
    private final OllamaClient ollama;
    private final PdfService pdfs;

    public TailorService(JobRepository jobs, ProfileService profiles, OllamaClient ollama, PdfService pdfs) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.ollama = ollama;
        this.pdfs = pdfs;
    }

    /** Checks the profile is ready; returns a problem to show the user, or null. */
    public String problem(Profile p) {
        if (!p.hasResume()) {
            return "Upload your resume on the Profile tab first.";
        }
        if (!p.hasContactDetails()) {
            return "Fill in at least your first name and email on the Profile tab first.";
        }
        return null;
    }

    public void startInBackground(long jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        if (job.getTailorState() == TaskState.RUNNING) {
            return;
        }
        job.setTailorState(TaskState.RUNNING);
        job.setTailorMessage("Writing with " + profiles.get().getOllamaModel() + "… usually 30 seconds to 3 minutes.");
        jobs.save(job);
        Thread.ofVirtual().name("tailor-" + jobId).start(() -> run(jobId));
    }

    private void run(long jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        Profile profile = profiles.get();
        try {
            JsonNode out = ollama.generateJson(profile, prompt(job, profile), java.util.List.of("summary", "cover_letter"));
            job.setTailoredSummary(clean(out.path("summary").asString()));
            job.setCoverLetter(clean(out.path("cover_letter").asString()));
            job.setTailoredAt(Instant.now());
            pdfs.writeAll(job, profile);
            job.setTailorState(TaskState.DONE);
            job.setTailorMessage("Ready. Read both PDFs and edit the text below if anything is off.");
            if (job.getStatus() == JobStatus.FOUND || job.getStatus() == JobStatus.SHORTLISTED) {
                job.setStatus(JobStatus.TAILORED);
                job.setStatusChangedAt(Instant.now());
            }
        } catch (Exception e) {
            if (hasCause(e, java.net.SocketTimeoutException.class)) {
                fail(job, "The local AI took longer than 6 minutes. Close other heavy apps and try again,"
                        + " or pick a smaller model on the Profile tab.");
            } else if (hasCause(e, tools.jackson.core.JacksonException.class)) {
                fail(job, "The AI's answer came back cut off. Press the button to try again.");
            } else if (hasCause(e, java.net.ConnectException.class) || e instanceof ResourceAccessException) {
                fail(job, "Can't reach Ollama at " + profile.getOllamaUrl() + ". Install it from ollama.com, run 'ollama pull "
                        + profile.getOllamaModel() + "', and try again.");
            } else {
                log.warn("Tailoring job {} failed", jobId, e);
                fail(job, "Tailoring failed: " + e.getMessage());
            }
        }
        jobs.save(job);
    }

    private static boolean hasCause(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (type.isInstance(t)) {
                return true;
            }
        }
        return false;
    }

    /** Rebuilds the PDFs after you edit the summary or cover letter by hand (no AI). */
    public void saveEdits(long jobId, String summary, String coverLetter) throws java.io.IOException {
        Job job = jobs.findById(jobId).orElseThrow();
        job.setTailoredSummary(clean(summary));
        job.setCoverLetter(clean(coverLetter));
        if (job.getTailoredAt() == null) {
            job.setTailoredAt(Instant.now());
        }
        pdfs.writeAll(job, profiles.get());
        job.setTailorState(TaskState.DONE);
        job.setTailorMessage("Saved your edits and rebuilt the PDFs.");
        jobs.save(job);
    }

    private static void fail(Job job, String message) {
        job.setTailorState(TaskState.ERROR);
        job.setTailorMessage(message.length() > 1000 ? message.substring(0, 1000) : message);
    }

    private static String prompt(Job job, Profile p) {
        return """
                You are helping a job seeker apply for a job. Use ONLY facts found in their resume below.
                Never invent employers, job titles, years, degrees, numbers, or skills they do not have.
                Do not claim soft skills (communication, leadership, teamwork) unless the resume shows them.
                If the job wants something the resume does not show, do not claim it.
                Prefer specific facts from the resume (technologies, projects, numbers) over general praise.

                Write two things:
                1. "summary": a 3-4 sentence professional summary for the top of the resume, aimed at this job.
                   Do not use "I"; write it like "Backend developer with ... experienced in ...".
                2. "cover_letter": a cover letter of 180-250 words in 3 short paragraphs separated by a blank line.
                   Start with "Dear Hiring Team," and end with "Regards,\\n%s".
                   Paragraph 1: the role and why this company. Paragraph 2: 2-3 concrete matches between the
                   resume and the job, each naming a real project, job or result from the resume (with its numbers). Paragraph 3: short, confident close. Plain, natural English, no clichés.

                JOB TITLE: %s
                COMPANY: %s
                LOCATION: %s

                JOB DESCRIPTION:
                %s

                CANDIDATE: %s
                CANDIDATE RESUME:
                %s
                """.formatted(
                p.getFullName(),
                job.getTitle(), job.getCompany(), job.getLocation(),
                cut(job.getDescription(), 6000),
                p.getFullName(),
                cut(p.getResumeText(), 7000));
    }

    private static String cut(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "\n[...]";
    }

    private static String clean(String s) {
        return s == null ? null : s.replace("\r", "").strip();
    }
}
