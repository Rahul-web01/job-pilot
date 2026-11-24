package com.jobpilot.outreach;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;

/**
 * Finds HR / hiring emails for a job, for free:
 * 1. addresses written in the job post,
 * 2. addresses published on the company's own public pages (careers, contact, about…), respecting robots.txt,
 * 3. if nothing HR-like is found, common inboxes like careers@ (marked as guesses).
 * It never logs in anywhere and never scrapes LinkedIn.
 */
@Service
public class EmailFinderService {

    private static final Logger log = LoggerFactory.getLogger(EmailFinderService.class);

    static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final List<String> PAGES = List.of("", "/careers", "/jobs", "/contact", "/contact-us", "/about",
            "/about-us", "/company", "/team");
    private static final List<String> HR_WORDS = List.of("hr", "career", "recruit", "talent", "job", "hiring",
            "people", "hire", "resume", "cv");
    private static final List<String> SKIP_WORDS = List.of("noreply", "no-reply", "donotreply", "privacy", "legal",
            "abuse", "security", "gdpr", "dpo", "unsubscribe", "billing", "invoice", "example", "sentry", "wixpress",
            // inboxes that aren't for hiring: emailing them about a job looks careless
            "sales", "marketing", "press", "media", "partner", "investor", "ir@", "advertis", "procurement", "vendor");
    private static final Set<String> FILE_ENDINGS = Set.of(".png", ".jpg", ".jpeg", ".gif", ".svg", ".webp", ".css", ".js");

    private final JobRepository jobs;
    private final ContactRepository contacts;
    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    public EmailFinderService(JobRepository jobs, ContactRepository contacts) {
        this.jobs = jobs;
        this.contacts = contacts;
    }

    public record Found(String email, ContactSource source, int confidence, String where) {
    }

    /** Runs the search for one job and saves what it finds. Returns a short summary for the page. */
    public String find(long jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        String domain = job.getCompanyDomain();
        if (domain == null) {
            domain = Domains.guess(job);
            if (domain != null) {
                job.setCompanyDomain(domain);
                jobs.save(job);
            }
        }

        Map<String, Found> found = new LinkedHashMap<>();
        // 1. In the job post: any domain counts (some companies use a separate hiring domain)
        for (String e : extract(job.getDescription())) {
            add(found, new Found(e, ContactSource.JOB_POST, rank(e, ContactSource.JOB_POST), "job post"));
        }
        if (domain == null) {
            save(jobId, found);
            return found.isEmpty()
                    ? "Couldn't work out the company's website. Type it in the box (e.g. stripe.com) and search again."
                    : "Found " + found.size() + " in the job post. Add the company website to search it too.";
        }

        // 2. The company's own public pages
        int pages = 0;
        Set<String> disallowed = robotsDisallowed(domain);
        for (String path : PAGES) {
            if (isDisallowed(path.isEmpty() ? "/" : path, disallowed)) {
                continue;
            }
            String html = fetch("https://" + domain + path);
            if (html == null) {
                html = fetch("https://www." + domain + path);
            }
            if (html == null) {
                continue;
            }
            pages++;
            for (String e : extractFromHtml(html)) {
                if (e.endsWith("@" + domain) || e.endsWith("." + domain)) {
                    add(found, new Found(e, ContactSource.WEBSITE, rank(e, ContactSource.WEBSITE),
                            domain + (path.isEmpty() ? "/" : path)));
                }
            }
        }

        // 3. Common inboxes, only as clearly-marked guesses
        boolean hasHrInbox = found.keySet().stream().anyMatch(EmailFinderService::looksLikeHr);
        int guessed = 0;
        if (!hasHrInbox && Domains.hasMx(domain)) {
            for (String local : List.of("careers", "hr", "recruiting")) {
                add(found, new Found(local + "@" + domain, ContactSource.GUESSED, ContactSource.GUESSED.getConfidence(),
                        "common inbox name"));
                guessed++;
            }
        }
        save(jobId, found);
        int real = found.size() - guessed;
        return "Checked the job post and " + pages + " page" + (pages == 1 ? "" : "s") + " on " + domain + ": found "
                + real + " published address" + (real == 1 ? "" : "es")
                + (guessed > 0 ? " and added " + guessed + " common inbox guesses" : "") + ".";
    }

    /**
     * For a person you found yourself (e.g. a recruiter's name on the careers page or LinkedIn):
     * adds the usual company email patterns as guesses. Returns how many were added.
     */
    public int addPersonGuesses(long jobId, String fullName, String role) {
        Job job = jobs.findById(jobId).orElseThrow();
        String domain = job.getCompanyDomain();
        String[] parts = fullName.strip().toLowerCase(Locale.ROOT).replaceAll("[^a-z ]", "").split("\\s+");
        if (domain == null || parts.length == 0 || parts[0].isEmpty()) {
            return 0;
        }
        String f = parts[0];
        String l = parts.length > 1 ? parts[parts.length - 1] : "";
        List<String> locals = l.isEmpty() ? List.of(f)
                : List.of(f + "." + l, f, f + l, f.charAt(0) + l, f + "_" + l, f + l.charAt(0));
        int added = 0;
        for (String local : locals) {
            String email = local + "@" + domain;
            if (contacts.findByJobIdAndEmailIgnoreCase(jobId, email).isEmpty()) {
                Contact c = new Contact();
                c.setJobId(jobId);
                c.setEmail(email);
                c.setName(capitalize(fullName.strip()));
                c.setRole(role == null || role.isBlank() ? null : role.strip());
                c.setSource(ContactSource.GUESSED);
                // first.last is the most common pattern, so rank it a little higher
                c.setConfidence(added == 0 ? 30 : 15);
                c.setFoundAt("pattern for " + domain);
                c.setCreatedAt(Instant.now());
                contacts.save(c);
                added++;
            }
        }
        return added;
    }

    /** An address you already know (e.g. from a LinkedIn post or a friend). */
    public void addManual(long jobId, String email, String name, String role) {
        String e = email.strip().toLowerCase(Locale.ROOT);
        if (!EMAIL.matcher(e).matches()) {
            throw new IllegalArgumentException("That doesn't look like an email address.");
        }
        Contact c = contacts.findByJobIdAndEmailIgnoreCase(jobId, e).orElseGet(Contact::new);
        c.setJobId(jobId);
        c.setEmail(e);
        c.setName(name == null || name.isBlank() ? c.getName() : capitalize(name.strip()));
        c.setRole(role == null || role.isBlank() ? c.getRole() : role.strip());
        c.setSource(ContactSource.MANUAL);
        c.setConfidence(ContactSource.MANUAL.getConfidence());
        c.setFoundAt("added by you");
        if (c.getCreatedAt() == null) {
            c.setCreatedAt(Instant.now());
        }
        contacts.save(c);
    }

    private void save(long jobId, Map<String, Found> found) {
        for (Found f : found.values()) {
            var existing = contacts.findByJobIdAndEmailIgnoreCase(jobId, f.email());
            if (existing.isPresent()) {
                continue;
            }
            Contact c = new Contact();
            c.setJobId(jobId);
            c.setEmail(f.email());
            c.setSource(f.source());
            c.setConfidence(f.confidence());
            c.setFoundAt(f.where());
            c.setCreatedAt(Instant.now());
            contacts.save(c);
        }
    }

    private static void add(Map<String, Found> found, Found f) {
        Found old = found.get(f.email());
        if (old == null || old.confidence() < f.confidence()) {
            found.put(f.email(), f);
        }
    }

    /** Higher for HR-type inboxes, lower for general ones like info@ or support@. */
    static int rank(String email, ContactSource source) {
        String local = email.substring(0, email.indexOf('@'));
        int base = source.getConfidence();
        if (looksLikeHr(email)) {
            return Math.min(99, base + 5);
        }
        if (local.matches("(info|hello|contact|support|help|sales|admin|team|office|enquiries|inquiries)")) {
            return base - 35;
        }
        return base - 10; // probably a person
    }

    static boolean looksLikeHr(String email) {
        String local = email.substring(0, email.indexOf('@'));
        return HR_WORDS.stream().anyMatch(local::contains);
    }

    /** Emails written in plain text. */
    static Set<String> extract(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null) {
            return out;
        }
        Matcher m = EMAIL.matcher(text);
        while (m.find()) {
            String e = clean(m.group());
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    /** Emails on a web page: visible text, mailto: links and Cloudflare-hidden addresses. */
    static Set<String> extractFromHtml(String html) {
        var doc = Jsoup.parse(html);
        Set<String> out = new LinkedHashSet<>(extract(doc.text()));
        for (var a : doc.select("a[href^=mailto:]")) {
            String addr = a.attr("href").substring(7).split("\\?")[0];
            out.addAll(extract(java.net.URLDecoder.decode(addr, java.nio.charset.StandardCharsets.UTF_8)));
        }
        for (var el : doc.select("[data-cfemail]")) {
            String decoded = decodeCloudflare(el.attr("data-cfemail"));
            if (decoded != null) {
                out.addAll(extract(decoded));
            }
        }
        return out;
    }

    static String clean(String raw) {
        String e = raw.toLowerCase(Locale.ROOT).replaceAll("^[._-]+|[._-]+$", "");
        if (SKIP_WORDS.stream().anyMatch(e::contains)) {
            return null;
        }
        for (String ending : FILE_ENDINGS) {
            if (e.endsWith(ending)) {
                return null; // e.g. logo@2x.png
            }
        }
        return EMAIL.matcher(e).matches() ? e : null;
    }

    /** Cloudflare "email protection" hides addresses as hex XOR'd with the first byte. */
    static String decodeCloudflare(String hex) {
        try {
            int key = Integer.parseInt(hex.substring(0, 2), 16);
            StringBuilder sb = new StringBuilder();
            for (int i = 2; i + 1 < hex.length(); i += 2) {
                sb.append((char) (Integer.parseInt(hex.substring(i, i + 2), 16) ^ key));
            }
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** Paths that robots.txt asks all bots not to visit. */
    private Set<String> robotsDisallowed(String domain) {
        Set<String> out = new LinkedHashSet<>();
        String txt = fetch("https://" + domain + "/robots.txt");
        if (txt == null) {
            return out;
        }
        boolean forAll = false;
        for (String line : txt.split("\n")) {
            String l = line.strip();
            String lower = l.toLowerCase(Locale.ROOT);
            if (lower.startsWith("user-agent:")) {
                forAll = l.substring(11).strip().equals("*");
            } else if (forAll && lower.startsWith("disallow:")) {
                String path = l.substring(9).strip();
                if (!path.isEmpty()) {
                    out.add(path);
                }
            }
        }
        return out;
    }

    static boolean isDisallowed(String path, Set<String> disallowed) {
        for (String d : disallowed) {
            if (d.equals("/") || path.startsWith(d.replace("*", ""))) {
                return true;
            }
        }
        return false;
    }

    /** GETs a page (max ~1 MB), or null if it fails. */
    private String fetch(String url) {
        try {
            var req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(8))
                    .header("User-Agent", "Mozilla/5.0 (JobPilot personal job search)")
                    .header("Accept", "text/html,text/plain")
                    .GET().build();
            var res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() != 200) {
                return null;
            }
            String body = res.body();
            return body.length() > 1_000_000 ? body.substring(0, 1_000_000) : body;
        } catch (Exception e) {
            log.debug("Fetch {} failed: {}", url, e.getMessage());
            return null;
        }
    }

    private static String capitalize(String name) {
        List<String> out = new ArrayList<>();
        for (String w : name.split("\\s+")) {
            out.add(w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1));
        }
        return String.join(" ", out);
    }
}
