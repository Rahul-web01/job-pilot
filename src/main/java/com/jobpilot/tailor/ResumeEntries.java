package com.jobpilot.tailor;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Splits resume text into its roles and projects, and picks the ones that best match a job.
 * The cover letter is built from these picked entries, so the local AI only ever rewrites a few
 * lines it can see, instead of the whole resume (where small models mix facts up).
 */
final class ResumeEntries {

    /** One role or project: which section it came from, its title line, and its detail lines. */
    record Entry(boolean experience, String title, String body) {

        String text() {
            return title + "\n" + body;
        }

        /** The title without dates or the "| tech list" part, e.g. "Java Intern, Adyapan". */
        String name() {
            return title.replaceAll("\\s*\\|.*$", "")
                    .replaceAll("\\s*((Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]*\\.?\\s*)?(19|20)\\d{2}.*$", "").strip();
        }
    }

    private static final Pattern YEAR = Pattern.compile("\\b(19|20)\\d{2}\\b");
    private static final Pattern BULLET = Pattern.compile("^[•\\-–◦▪*·]\\s*");
    private static final Set<String> STOP = Set.of("with", "that", "this", "from", "into", "using", "used", "their",
            "your", "they", "have", "will", "work", "role", "team", "years", "year", "experience", "built", "build",
            "and", "the", "for", "each", "every", "across", "over", "under", "about", "also", "which", "while");

    /** Technology names: they count triple when matching a job, and the letter checker knows them. */
    static final List<String> TECH = List.of("java", "python", "javascript", "typescript", "kotlin", "go", "rust",
            "c++", "spring", "spring boot", "kafka", "docker", "kubernetes", "aws", "gcp", "azure", "react", "angular",
            "node", "mysql", "postgresql", "mongodb", "redis", "h2", "sqlite", "graphql", "grpc", "blockchain", "hibernate",
            "jpa", "junit", "mockito", "playwright", "selenium", "ollama", "llm", "microservices", "terraform", "jenkins",
            "rabbitmq", "elasticsearch", "spark", "hadoop", "tailwind", "thymeleaf", "rest");

    private ResumeEntries() {
    }

    static List<Entry> parse(String resume) {
        List<Entry> out = new ArrayList<>();
        if (resume == null) {
            return out;
        }
        String section = "";
        String title = null;
        StringBuilder body = new StringBuilder();
        boolean exp = false;
        for (String raw : resume.split("\\R")) {
            String line = raw.replace('\u00A0', ' ').strip();
            if (line.isEmpty()) {
                continue;
            }
            String heading = heading(line);
            if (heading != null) {
                title = flush(out, title, body, exp);
                section = heading;
                continue;
            }
            boolean inEntries = section.contains("EXPERIENCE") || section.contains("EMPLOYMENT")
                    || section.contains("INTERNSHIP") || section.contains("PROJECT");
            if (!inEntries) {
                continue;
            }
            boolean bullet = BULLET.matcher(line).find();
            if (!bullet && YEAR.matcher(line).find()) {
                title = flush(out, title, body, exp);
                title = line;
                exp = !section.contains("PROJECT");
                continue;
            }
            if (title == null || isLinkLine(line)) {
                continue;
            }
            String text = BULLET.matcher(line).replaceFirst("");
            if (body.length() == 0) {
                body.append(text);
            } else if (body.charAt(body.length() - 1) == '-') {
                body.append(text);
            } else {
                body.append(bullet || endsSentence(body) ? "\n" : " ").append(text);
            }
        }
        flush(out, title, body, exp);
        return out;
    }

    /** The best-matching role first (if any), then the best-matching project; at most 2 entries. */
    static List<Entry> pick(List<Entry> entries, String jobText) {
        String job = nz(jobText).toLowerCase(Locale.ROOT);
        Comparator<Entry> byScore = Comparator.comparingInt((Entry e) -> score(e, job)).reversed();
        List<Entry> out = new ArrayList<>();
        entries.stream().filter(Entry::experience).sorted(byScore).findFirst().ifPresent(out::add);
        entries.stream().filter(e -> !e.experience()).sorted(byScore).findFirst().ifPresent(out::add);
        if (out.size() < 2) {
            entries.stream().filter(e -> !out.contains(e)).sorted(byScore).limit(2 - out.size()).forEach(out::add);
        }
        return out;
    }

    /** Words shared with the job (technologies count triple), plus one point per technology the entry shows. */
    static int score(Entry e, String jobLower) {
        Set<String> words = new HashSet<>();
        for (String w : e.text().toLowerCase(Locale.ROOT).split("[^a-z0-9+#]+")) {
            if (w.length() >= 3 && !STOP.contains(w)) {
                words.add(w);
            }
        }
        int score = 0;
        for (String w : words) {
            boolean tech = TECH.contains(w);
            if (Pattern.compile("\\b" + Pattern.quote(w) + "(s|es)?\\b").matcher(jobLower).find()) {
                score += tech ? 3 : 1;
            }
            if (tech) {
                score += 1; // entries that show more real technology are stronger evidence
            }
        }
        return score;
    }

    /** "E X P E R I E N C E", "Experience", "PROJECTS:" -> upper-case name; null if not a heading. */
    static String heading(String line) {
        String squashed = line.replace(" ", "").replace(":", "");
        boolean spacedCaps = line.matches("([A-Z&] ?){4,}") || line.matches("[A-Z][A-Z &]{3,30}:?");
        boolean named = squashed.matches("(?i)(work|professional|relevant)?(experience|employment(history)?|internships?|"
                + "projects?|personalprojects|academicprojects|education|skills|technicalskills|certifications?|"
                + "achievements|awards|summary|profile|objective|publications|languages|interests)");
        return spacedCaps || named ? squashed.toUpperCase(Locale.ROOT) : null;
    }

    private static boolean isLinkLine(String line) {
        return line.matches("(?i)^(live|github|demo|code|link|website|github & demo)\\s*:.*");
    }

    private static boolean endsSentence(StringBuilder b) {
        char c = b.charAt(b.length() - 1);
        return c == '.' || c == '!' || c == '?';
    }

    private static String flush(List<Entry> out, String title, StringBuilder body, boolean exp) {
        if (title != null && body.length() > 0) {
            out.add(new Entry(exp, title, body.toString().strip()));
        }
        body.setLength(0);
        return null;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
