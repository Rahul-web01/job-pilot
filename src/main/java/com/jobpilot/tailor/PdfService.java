package com.jobpilot.tailor;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

import org.jsoup.Jsoup;
import org.jsoup.helper.W3CDom;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.jobpilot.job.Job;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;

/** Builds the tailored resume and cover letter PDFs for one job, in ./data/applications/<job id>/. */
@Service
public class PdfService {

    public static final String RESUME = "resume.pdf";
    public static final String COVER_LETTER = "cover-letter.pdf";

    private final TemplateEngine templates;
    private final ProfileService profiles;

    public PdfService(TemplateEngine templates, ProfileService profiles) {
        this.templates = templates;
        this.profiles = profiles;
    }

    public Path dir(Job job) {
        return profiles.dataDir().resolve("applications").resolve(String.valueOf(job.getId()));
    }

    public Path file(Job job, String name) {
        Path p = dir(job).resolve(name);
        return Files.exists(p) ? p : null;
    }

    /** File name a recruiter sees, e.g. "Asha-Verma-Resume-Stripe.pdf". */
    public static String niceName(Profile p, Job job, String kind) {
        String base = (p.getFullName().isBlank() ? "My" : p.getFullName()) + " " + kind + " " + job.getCompany();
        return base.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("(^-|-$)", "") + ".pdf";
    }

    public void writeAll(Job job, Profile profile) throws IOException {
        Files.createDirectories(dir(job));
        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("p", profile);
        ctx.setVariable("job", job);
        ctx.setVariable("skills", orderedSkills(job, profile));
        ctx.setVariable("body", body(profile.getResumeText(), profile));
        ctx.setVariable("coverParagraphs", paragraphs(job.getCoverLetter()));
        render(templates.process("pdf/resume", ctx), dir(job).resolve(RESUME));
        render(templates.process("pdf/cover-letter", ctx), dir(job).resolve(COVER_LETTER));
    }

    /** Skills this job asks for first, then the rest of your skills. */
    private static List<String> orderedSkills(Job job, Profile profile) {
        var set = new LinkedHashSet<String>();
        set.addAll(Profile.lines(job.getMatchedSkills()));
        set.addAll(Profile.lines(profile.getSkills()));
        return set.stream().map(PdfService::prettySkill).toList();
    }

    private static final java.util.Map<String, String> SKILL_NAMES = java.util.Map.ofEntries(
            java.util.Map.entry("aws", "AWS"), java.util.Map.entry("gcp", "GCP"), java.util.Map.entry("sql", "SQL"),
            java.util.Map.entry("api", "API"), java.util.Map.entry("rest api", "REST API"),
            java.util.Map.entry("rest apis", "REST APIs"), java.util.Map.entry("mysql", "MySQL"),
            java.util.Map.entry("postgresql", "PostgreSQL"), java.util.Map.entry("mongodb", "MongoDB"),
            java.util.Map.entry("javascript", "JavaScript"), java.util.Map.entry("typescript", "TypeScript"),
            java.util.Map.entry("node.js", "Node.js"), java.util.Map.entry("nodejs", "Node.js"),
            java.util.Map.entry("html", "HTML"), java.util.Map.entry("css", "CSS"), java.util.Map.entry("ci/cd", "CI/CD"),
            java.util.Map.entry("dsa", "DSA"), java.util.Map.entry("oop", "OOP"), java.util.Map.entry("jpa", "JPA"),
            java.util.Map.entry("graphql", "GraphQL"), java.util.Map.entry("redis", "Redis"),
            java.util.Map.entry("kubernetes", "Kubernetes"), java.util.Map.entry("github", "GitHub"));

    static String prettySkill(String s) {
        String key = s.toLowerCase(Locale.ROOT).trim();
        if (SKILL_NAMES.containsKey(key)) {
            return SKILL_NAMES.get(key);
        }
        StringBuilder out = new StringBuilder();
        for (String w : key.split(" ")) {
            if (!w.isEmpty()) {
                out.append(out.length() > 0 ? " " : "").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return out.toString();
    }

    /** One rendered line of the resume body: a section heading or a normal line. */
    public record Line(String text, boolean heading) {
    }

    private static final java.util.regex.Pattern HEADING = java.util.regex.Pattern.compile(
            "(?i)^(work |professional |relevant )?(experience|employment( history)?|projects?|personal projects|academic projects|"
                    + "education|skills|technical skills|key skills|core skills|certifications?|achievements|awards|"
                    + "internships?|publications|languages|interests|hobbies|activities|extra-?curricular( activities)?|"
                    + "positions of responsibility|leadership|volunteering|courses|coursework|summary|profile|objective|"
                    + "career objective|professional summary|about me)\\s*:?$");

    /**
     * Your resume text, without its own name/contact header (the PDF has its own), with
     * section headings detected so they can be styled.
     */
    static List<Line> body(String text, Profile p) {
        List<Line> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        List<String> lines = text.lines().map(String::strip).toList();
        int i = 0;
        // skip the header block: blank lines, your name, and lines with your email/phone/links
        while (i < lines.size() && i < 6 && isHeaderLine(lines.get(i), p)) {
            i++;
        }
        for (; i < lines.size(); i++) {
            String l = lines.get(i);
            if (l.isEmpty()) {
                continue;
            }
            out.add(new Line(l, HEADING.matcher(l).matches()));
        }
        return out;
    }

    private static boolean isHeaderLine(String l, Profile p) {
        String low = l.toLowerCase(Locale.ROOT);
        return l.isEmpty()
                || (!p.getFullName().isBlank() && low.equals(p.getFullName().toLowerCase(Locale.ROOT)))
                || low.contains("@")
                || low.contains("linkedin.com")
                || low.contains("github.com")
                || l.replaceAll("[^0-9]", "").length() >= 10;
    }

    private static List<String> paragraphs(String text) {
        if (text == null) {
            return List.of();
        }
        return List.of(text.split("\\n\\s*\\n")).stream().map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    private static void render(String html, Path target) throws IOException {
        var doc = new W3CDom().fromJsoup(Jsoup.parse(html));
        try (OutputStream os = Files.newOutputStream(target)) {
            new PdfRendererBuilder()
                    .useFastMode()
                    .withW3cDocument(doc, null)
                    .toStream(os)
                    .run();
        }
    }
}
