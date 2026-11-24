package com.jobpilot.outreach;

import java.net.URI;
import java.util.Hashtable;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import javax.naming.directory.Attribute;
import javax.naming.directory.InitialDirContext;

import com.jobpilot.job.Job;

/** Works out a company's email domain and checks it can receive mail. Free: uses plain DNS. */
public final class Domains {

    /** Job-board hosts: these are never the company's own domain. */
    private static final Set<String> JOB_BOARDS = Set.of(
            "greenhouse.io", "lever.co", "remotive.com", "workable.com", "ashbyhq.com", "smartrecruiters.com",
            "myworkdayjobs.com", "icims.com", "linkedin.com", "indeed.com", "naukri.com", "wellfound.com");

    private static final Set<String> TWO_PART_SUFFIXES = Set.of("co.in", "co.uk", "com.au", "co.jp", "com.br", "com.sg");

    private Domains() {
    }

    /** Best guess for the company's domain, or null if none can be confirmed. */
    public static String guess(Job job) {
        String fromUrl = registrable(host(job.getUrl()));
        if (fromUrl != null && !isJobBoard(fromUrl) && hasMx(fromUrl)) {
            return fromUrl;
        }
        String base = slug(job.getBoard() != null ? job.getBoard() : job.getCompany());
        if (base == null) {
            return null;
        }
        for (String tld : List.of(".com", ".in", ".io", ".co", ".ai")) {
            if (hasMx(base + tld)) {
                return base + tld;
            }
        }
        return null;
    }

    /** Cleans what the user typed: "https://www.stripe.com/jobs" becomes "stripe.com". */
    public static String normalize(String input) {
        if (input == null || input.isBlank()) {
            return null;
        }
        String s = input.strip().toLowerCase(Locale.ROOT);
        if (!s.contains("://")) {
            s = "https://" + s;
        }
        return registrable(host(s));
    }

    static String host(String url) {
        try {
            return url == null ? null : URI.create(url.strip()).getHost();
        } catch (Exception e) {
            return null;
        }
    }

    /** "careers.stripe.com" → "stripe.com", "jobs.tata.co.in" → "tata.co.in". */
    static String registrable(String host) {
        if (host == null || !host.contains(".")) {
            return null;
        }
        String[] parts = host.toLowerCase(Locale.ROOT).split("\\.");
        int n = parts.length;
        if (n >= 3 && TWO_PART_SUFFIXES.contains(parts[n - 2] + "." + parts[n - 1])) {
            return parts[n - 3] + "." + parts[n - 2] + "." + parts[n - 1];
        }
        return parts[n - 2] + "." + parts[n - 1];
    }

    static boolean isJobBoard(String domain) {
        return JOB_BOARDS.contains(domain);
    }

    /** Lowercase letters and digits only: "Razorpay Software" → "razorpaysoftware". */
    static String slug(String name) {
        if (name == null) {
            return null;
        }
        String s = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return s.isEmpty() ? null : s;
    }

    /** True if the domain has an MX record, i.e. it can receive email. */
    public static boolean hasMx(String domain) {
        if (domain == null || domain.isBlank()) {
            return false;
        }
        try {
            var env = new Hashtable<String, String>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("com.sun.jndi.dns.timeout.initial", "2000");
            env.put("com.sun.jndi.dns.timeout.retries", "1");
            var ctx = new InitialDirContext(env);
            Attribute mx = ctx.getAttributes(domain, new String[] { "MX" }).get("MX");
            ctx.close();
            return mx != null && mx.size() > 0;
        } catch (Exception e) {
            return false;
        }
    }
}
