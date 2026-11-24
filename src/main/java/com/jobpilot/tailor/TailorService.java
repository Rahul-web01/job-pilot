package com.jobpilot.tailor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

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

/**
 * Writes a tailored summary and cover letter for a job with the local AI, then builds the PDFs.
 * Only the summary and cover letter are AI-written; your experience, projects and education are copied
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
            String context = context(job, profile);
            String summary = ollama.generate(profile, context + SUMMARY_TASK, 200);
            String letter = writeLetter(job, profile, context);
            job.setTailoredSummary(firstSentences(stripPreamble(summary), 4));
            job.setCoverLetter(letter);
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

    private static final String RULES = """

            Use ONLY facts from the resume. Never invent employers, job titles, years, degrees, numbers or skills.
            Do not claim soft skills (communication, leadership, teamwork) unless the resume shows them.
            If the job asks for something the resume does not show, do not claim it.
            Reply with the text only: no title, no introduction, no notes.
            """;

    private static final String SUMMARY_TASK = RULES + """

            Write a 3-sentence professional summary for the top of this resume, aimed at this job.
            Do not use "I". Start like "Backend developer with ..." (use the candidate's own field).
            """;

    /**
     * Writes the cover letter, checks it, and if it has problems (cliches, a number the resume doesn't
     * contain, wrong length, no company name) asks the model once more to fix exactly those problems.
     * Keeps whichever draft has fewer problems.
     */
    private String freeLetter(Job job, Profile profile, String context) {
        String task = coverTask(profile, job);
        String best = polish(ollama.generate(profile, context + task, 700), profile);
        List<String> issues = letterProblems(best, job, profile);
        for (int attempt = 0; attempt < 2 && !issues.isEmpty(); attempt++) {
            String fix = context + task + """

                    Here is a draft that has problems:
                    ---
                    %s
                    ---
                    Rewrite the whole letter and fix these problems:
                    - %s
                    """.formatted(best, String.join("\n- ", issues));
            String retry = polish(ollama.generate(profile, fix, 700), profile);
            List<String> retryIssues = letterProblems(retry, job, profile);
            if (retryIssues.size() <= issues.size()) {
                best = retry;
                issues = retryIssues;
            }
        }
        return best;
    }

    /**
     * Builds the cover letter from the 2 resume entries that best match the job. The local AI only rewrites
     * one entry at a time (and writes one sentence about the team from the job post), and every AI paragraph
     * is checked; if it adds a fact the entry doesn't have, a plain rewrite of the entry is used instead.
     * Resumes that can't be split into entries fall back to a whole-letter prompt.
     */
    private String writeLetter(Job job, Profile profile, String context) {
        List<ResumeEntries.Entry> picked = ResumeEntries.pick(
                ResumeEntries.parse(profile.getResumeText()), nz(job.getTitle()) + " " + nz(job.getDescription()));
        if (picked.isEmpty()) {
            return freeLetter(job, profile, context);
        }
        StringBuilder out = new StringBuilder("Dear Hiring Team,\n\n").append(intro(job, profile));
        for (ResumeEntries.Entry e : picked) {
            out.append("\n\n").append(entryParagraph(e, profile));
        }
        String link = workLink(profile);
        out.append("\n\nI'd welcome a short conversation about how I can help the ").append(nz(job.getCompany()).strip())
                .append(" team. ").append(link.equals("the attached resume") ? "My resume is attached." : "You can see my work at " + link + ".")
                .append("\n\nBest regards,\n").append(profile.getFullName());
        return out.toString();
    }

    private String intro(Job job, Profile profile) {
        String opening = "I'm applying for the " + nz(job.getTitle()).strip() + " role at " + nz(job.getCompany()).strip() + ".";
        String why = "";
        try {
            String raw = stripPreamble(ollama.generate(profile, """
                    JOB DESCRIPTION:
                    %s

                    In one sentence of at most 25 words, say what this team builds or is responsible for, using only facts
                    from the job description. Start with "The team". No praise words and no adjectives like exciting or innovative.
                    Reply with the sentence only.
                    """.formatted(cut(job.getDescription(), 5000)), 80));
            String sentence = firstSentence(raw.replaceAll("\\s+", " "));
            boolean ok = sentence.startsWith("The team") && sentence.split("\\s+").length <= 35
                    && CLICHES.stream().noneMatch(c -> sentence.toLowerCase(Locale.ROOT).contains(c));
            if (ok) {
                why = " " + sentence + " That is the kind of work I want to do next.";
            }
        } catch (RuntimeException e) {
            log.debug("Intro sentence failed, using the plain opening", e);
        }
        String who = whoLine(profile.getResumeText());
        return opening + why + (who.isEmpty() ? "" : " " + who);
    }

    /** "Final-year B.Tech CSE student with ..." from the resume summary, as "I'm a final-year ...". */
    static String whoLine(String resume) {
        if (resume == null) {
            return "";
        }
        StringBuilder summary = new StringBuilder();
        boolean in = false;
        for (String line : resume.split("\\R")) {
            String h = ResumeEntries.heading(line.strip());
            if (h != null) {
                if (in) {
                    break;
                }
                in = h.contains("SUMMARY") || h.contains("PROFILE") || h.contains("OBJECTIVE");
                continue;
            }
            if (in) {
                summary.append(line.strip()).append(' ');
            }
        }
        String first = firstSentence(summary.toString().strip());
        if (first.isEmpty() || first.split("\\s+").length > 35) {
            return "";
        }
        if (first.startsWith("I ") || first.startsWith("I'm")) {
            return first;
        }
        String lead = lcFirst(first);
        return "I'm " + ("aeiou".indexOf(Character.toLowerCase(lead.charAt(0))) >= 0 ? "an " : "a ") + lead;
    }

    /** " (May 2026 – Jun 2026)", " from Jul 2026 to Aug 2026" and similar, which read badly in a letter. */
    static final String DATE_RANGE = "\\s*\\(?(from |in |during )?(Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.? (19|20)\\d{2}"
            + "(\\s*(–|-|to)\\s*((Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.? )?((19|20)\\d{2}|present))?\\)?";

    private String entryParagraph(ResumeEntries.Entry e, Profile profile) {
        String prompt = """
                Rewrite these resume lines as one cover-letter paragraph of 2 or 3 sentences, in the first person.
                Use ONLY the facts in these lines. Keep their numbers exactly as written. Do not add any technology,
                number or claim that is not in them. No praise words and no dates.
                The paragraph must start with exactly these words: "%s"

                LINES:
                %s

                Reply with the paragraph only.
                """.formatted(lead(e), e.name() + "\n" + e.body());
        try {
            for (int attempt = 0; attempt < 2; attempt++) {
                String para = stripPreamble(ollama.generate(profile, prompt, 220)).replaceAll("\\s*\\n\\s*", " ");
                para = para.replaceAll("\\butiliz(e|es|ed|ing)\\b", "us$1").replaceAll(DATE_RANGE, "").replaceAll("\\s+([.,])", "$1");
                if (para.startsWith(lead(e).strip()) && paragraphProblems(para, e, profile).isEmpty()) {
                    return para;
                }
            }
        } catch (RuntimeException ex) {
            log.debug("Paragraph rewrite failed, using the plain version", ex);
        }
        return plainParagraph(e);
    }

    /** Facts the paragraph adds that the entry doesn't have. Empty when it's faithful. */
    static List<String> paragraphProblems(String para, ResumeEntries.Entry e, Profile p) {
        List<String> out = new ArrayList<>();
        String src = e.text().toLowerCase(Locale.ROOT).replace(",", "");
        String low = para.toLowerCase(Locale.ROOT);
        var m = java.util.regex.Pattern.compile("\\d[\\d,.]*").matcher(para);
        while (m.find()) {
            String n = m.group().replace(",", "").replaceAll("\\.$", "");
            if (!src.contains(n)) {
                out.add("number " + n);
            }
        }
        List<String> vocab = new ArrayList<>(ResumeEntries.TECH);
        Profile.lines(p.getSkills()).forEach(s -> vocab.add(s.toLowerCase(Locale.ROOT)));
        for (String t : vocab) {
            var re = java.util.regex.Pattern.compile("(?<![a-z0-9])" + java.util.regex.Pattern.quote(t) + "(?![a-z0-9])");
            if (re.matcher(low).find() && !re.matcher(src).find()) {
                out.add("technology " + t);
            }
        }
        CLICHES.stream().filter(low::contains).forEach(c -> out.add("phrase " + c));
        int words = para.split("\\s+").length;
        if (words < 20 || words > 110) {
            out.add("length " + words);
        }
        return out;
    }

    /** "As a Java Intern at Acme, I " for a role; "For JobPilot, I " for a project. */
    static String lead(ResumeEntries.Entry e) {
        String title = e.name();
        if (e.experience()) {
            String[] parts = title.split("\\s*[–—-]\\s+", 2)[0].split(",\\s*", 2);
            return parts.length == 2 ? "As a " + parts[0] + " at " + parts[1] + ", I " : "As a " + parts[0] + ", I ";
        }
        return "For " + title.split("\\s+[–—-]\\s+", 2)[0] + ", I ";
    }

    /** The entry's first two lines as sentences: "As a Java Intern at Acme, I built ... I also ...". */
    static String plainParagraph(ResumeEntries.Entry e) {
        String lead = lead(e);
        String[] lines = e.body().split("\\n");
        StringBuilder out = new StringBuilder(lead).append(lcFirst(lines[0]));
        if (lines.length > 1) {
            out.append(" I also ").append(lcFirst(lines[1]));
        }
        return out.toString().strip();
    }

    /** First sentence, without breaking on "B.Tech" or "e.g." (a sentence ends at ". " before a capital). */
    static String firstSentence(String s) {
        return s.isEmpty() ? s : s.split("(?<=[.!?])\\s+(?=[A-Z\"])", 2)[0].strip();
    }

    private static String lcFirst(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isLowerCase(s.charAt(1))) {
            return Character.toLowerCase(s.charAt(0)) + s.substring(1);
        }
        return s;
    }

    /** Cleans the model's reply: drops chatter, swaps filler words for plain ones, ensures the sign-off. */
    static String polish(String raw, Profile p) {
        String out = stripPreamble(raw)
                .replaceAll("\\butiliz(e|es|ed|ing)\\b", "us$1")
                .replaceAll("\\bUtiliz(e|es|ed|ing)\\b", "Us$1")
                .replaceAll("[ \\t]+\\n", "\n")
                .replaceAll("\\n{3,}", "\n\n");
        return finishLetter(out, p);
    }

    /** Phrases that make a cover letter read like a template. Matched case-insensitively. */
    static final List<String> CLICHES = List.of(
            "excited", "thrilled", "passion", "i believe", "dynamic", "leverage", "utilize", "utilise", "esteemed",
            "fast-paced", "team player", "hard-working", "hardworking", "perfect fit", "dream job",
            "to whom it may concern", "i am writing to", "synergy", "go-getter", "perfectly", "showcas", "honed",
            "honing", "eager", "i am confident", "i'm confident", "aligns with", "align with", "skill set", "skillset",
            "valuable asset", "great fit", "strong fit", "keen interest");

    /** What's wrong with a letter, as instructions the model can act on. Empty when it's good. */
    static List<String> letterProblems(String letter, Job job, Profile p) {
        List<String> out = new ArrayList<>();
        String low = letter.toLowerCase(Locale.ROOT);
        for (String c : CLICHES) {
            if (low.contains(c)) {
                out.add("Remove the phrase \"" + c + "\" and say something specific instead.");
            }
        }
        String facts = (nz(p.getResumeText()) + " " + nz(job.getDescription()) + " " + nz(job.getTitle())).replace(",", "");
        var m = java.util.regex.Pattern.compile("\\d[\\d,.]*").matcher(letter);
        while (m.find()) {
            String n = m.group().replace(",", "").replaceAll("\\.$", "");
            if (n.length() >= 2 && !facts.contains(n)) {
                out.add("The number " + n + " is not in the resume. Use only numbers that appear in the resume.");
            }
        }
        String company = nz(job.getCompany()).strip();
        if (!company.isEmpty() && !low.contains(company.toLowerCase(Locale.ROOT))) {
            out.add("Mention the company name, " + company + ", in the first paragraph.");
        }
        int words = letter.split("\\s+").length;
        if (words < 150) {
            out.add("It is too short (" + words + " words). Write 170 to 250 words.");
        } else if (words > 300) {
            out.add("It is too long (" + words + " words). Cut it to 170 to 250 words.");
        }
        if (!letter.stripLeading().startsWith("Dear")) {
            out.add("Start with \"Dear Hiring Team,\".");
        }
        return out;
    }

    /** Where the letter points the reader: portfolio, else GitHub, else the attached resume. */
    private static String workLink(Profile p) {
        String url = !nz(p.getPortfolioUrl()).isBlank() ? p.getPortfolioUrl() : nz(p.getGithubUrl());
        return url.isBlank() ? "the attached resume" : url.replaceFirst("^https?://(www\\.)?", "");
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String coverTask(Profile p, Job job) {
        return RULES + """

                Write a cover letter for the %s role at %s: 170 to 250 words, 4 short paragraphs separated by blank lines.

                First line: "Dear Hiring Team,"
                Paragraph 1 (2 sentences): name the exact role and the company. Give one specific reason this job fits,
                taken from the job description (its product, its team, or a technology it names). Say who the candidate
                is in one phrase from the resume (for example their degree and year, or their current role).
                Paragraph 2: the candidate's strongest work experience or internship that matches this job's main
                requirement (prefer paid or internship work over class projects): what they built, with which
                technologies, and one number from the resume.
                Paragraph 3: the project that best matches another requirement of this job, again with a number from the resume.
                Paragraph 4 (exactly 2 sentences): ask for a short conversation about how they can help the team,
                then say where to see their work: %s.
                End with "Best regards," and then "%s" on the next line.

                Style: write like a sharp engineer, not a template. Short sentences. Active verbs (built, shipped, tested, cut).
                Write in the first person ("I built ...").
                Never use these words: %s.
                """.formatted(job.getTitle(), job.getCompany(), workLink(p), p.getFullName(), String.join(", ", CLICHES));
    }

    private static String context(Job job, Profile p) {
        return """
                JOB: %s at %s (%s)
                JOB DESCRIPTION:
                %s

                CANDIDATE: %s
                CANDIDATE RESUME:
                %s
                """.formatted(
                job.getTitle(), job.getCompany(), job.getLocation(),
                cut(job.getDescription(), 6000),
                p.getFullName(),
                cut(p.getResumeText(), 7000));
    }

    /** Drops lines like "Here is a cover letter:" and surrounding quotes that small models add. */
    static String stripPreamble(String s) {
        String out = clean(s);
        out = out.replaceFirst("(?is)^\\s*(here(?:'s| is)[^\\n]*?:)\\s*", "");
        out = out.replaceAll("^\"|\"$", "").strip();
        return out;
    }

    /** Keeps at most n sentences, so a rambling answer still gives a tidy summary. */
    static String firstSentences(String s, int n) {
        var m = java.util.regex.Pattern.compile("[^.!?]+[.!?]+").matcher(s.replace("\n", " "));
        StringBuilder out = new StringBuilder();
        int count = 0;
        while (count < n && m.find()) {
            out.append(m.group().strip()).append(' ');
            count++;
        }
        return count == 0 ? s.strip() : out.toString().strip();
    }

    /** Makes sure the letter ends with your name, even if the model was cut off. */
    static String finishLetter(String s, Profile p) {
        String out = s.strip();
        if (!out.toLowerCase(java.util.Locale.ROOT).contains("regards")) {
            out += "\n\nBest regards,\n" + p.getFullName();
        }
        return out;
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
