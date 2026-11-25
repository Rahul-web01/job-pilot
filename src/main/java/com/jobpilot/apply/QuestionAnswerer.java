package com.jobpilot.apply;

import java.time.YearMonth;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.jobpilot.job.Job;
import com.jobpilot.profile.Profile;

/**
 * Decides how to answer one application question, from your Profile only.
 * It never invents facts: if the Profile has no answer, it says so and the question is left for you
 * (or, for open text questions, the caller may ask the local AI to draft one from your resume).
 */
public class QuestionAnswerer {

    /** Where an answer came from, shown in the report so you know what to double-check. */
    public enum Source { PROFILE, YOUR_ANSWERS, AI }

    /** Why a question was left alone. */
    public enum Skip { LEGAL, DEMOGRAPHIC, NO_ANSWER, UNKNOWN }

    /** A question we recognise, but your Profile has no answer for: never sent to the AI. */
    private static final Answer EMPTY = new Answer(null, List.of(), false, null, Skip.NO_ANSWER);

    /**
     * text: what to type (text boxes) or search for (dropdowns).
     * prefer: patterns tried against a dropdown's options, in order; the first option matching wins.
     * many: for multi-choice questions, every option matching any pattern is ticked.
     */
    public record Answer(String text, List<Pattern> prefer, boolean many, Source source, Skip skip) {
        static Answer of(String text, Source src) {
            return new Answer(text, List.of(exact(text), startsWith(text)), false, src, null);
        }
        static Answer choose(String text, Source src, String... patterns) {
            List<Pattern> ps = new ArrayList<>();
            for (String p : patterns) {
                ps.add(Pattern.compile(p, Pattern.CASE_INSENSITIVE));
            }
            ps.add(exact(text));
            ps.add(startsWith(text));
            return new Answer(text, ps, false, src, null);
        }
        static Answer skip(Skip why) {
            return new Answer(null, List.of(), false, null, why);
        }
        boolean answered() {
            return skip == null;
        }
    }

    private static final Pattern LEGAL = rx("\\bnda\\b|non-disclosure|indicate your agreement|by typing your (full )?name|"
            + "i acknowledge|acknowledg|i certify|i attest|i agree|consent|signature|sign here|terms and conditions|"
            + "privacy (notice|policy)");
    private static final Pattern DEMOGRAPHIC = rx("gender|sex\\b|race|ethnic|hispanic|latino|veteran|disabilit|sexual orientation|"
            + "transgender|caste|religion|marital|date of birth|\\bage\\b|nationality");

    private final Profile p;
    private final Job job;

    public QuestionAnswerer(Profile profile, Job job) {
        this.p = profile;
        this.job = job;
    }

    /** True for questions you must answer yourself: agreements and voluntary diversity questions. */
    public static boolean mustBeYou(String label) {
        String l = norm(label);
        return LEGAL.matcher(l).find() || DEMOGRAPHIC.matcher(l).find();
    }

    public Answer answer(String label) {
        String l = norm(label);
        if (LEGAL.matcher(l).find()) {
            return Answer.skip(Skip.LEGAL);
        }
        if (DEMOGRAPHIC.matcher(l).find()) {
            return Answer.skip(Skip.DEMOGRAPHIC);
        }
        Answer custom = custom(l);
        if (custom != null) {
            return custom;
        }
        Answer a = fromProfile(l);
        return a != null ? a : Answer.skip(Skip.UNKNOWN);
    }

    /** "words from the question = answer" lines from the Profile; all the words must appear. */
    private Answer custom(String l) {
        for (String line : Profile.lines(p.getCustomAnswers() == null ? null : p.getCustomAnswers().replace(",", "\u0001"))) {
            line = line.replace('\u0001', ',');
            int eq = line.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String[] words = norm(line.substring(0, eq)).split("\\s+");
            String value = line.substring(eq + 1).strip();
            boolean all = !value.isEmpty();
            for (String w : words) {
                all &= l.contains(w);
            }
            if (all) {
                return yesNo(value) != null ? yesNoAnswer(yesNo(value), Source.YOUR_ANSWERS) : Answer.of(value, Source.YOUR_ANSWERS);
            }
        }
        return null;
    }

    private Answer fromProfile(String l) {
        String company = norm(job.getCompany());
        // ---- Work rights ----
        if (has(l, "sponsorship|sponsor|visa")) {
            return set(p.getNeedsSponsorship()) ? yesNoAnswer(yes(p.getNeedsSponsorship()), Source.PROFILE) : EMPTY;
        }
        if (has(l, "eligible to work|authori[sz]ed to work|legally (able|allowed|permitted) to work|right to work|work permit")) {
            // Only when the job's country is where you live and you don't need sponsorship
            if (set(p.getCountry()) && "No".equalsIgnoreCase(p.getNeedsSponsorship()) && countryMatches(l)) {
                return yesNoAnswer(true, Source.PROFILE);
            }
            return EMPTY;
        }
        // ---- Where you are ----
        if (has(l, "^are you (currently )?(located|based|living|residing) in|do you (currently )?(live|reside) in")) {
            if (!set(p.getCountry()) && !set(p.getCity())) {
                return EMPTY;
            }
            boolean inPlace = (set(p.getCountry()) && l.contains(norm(p.getCountry())))
                    || (set(p.getCity()) && l.contains(norm(p.getCity())));
            return yesNoAnswer(inPlace, Source.PROFILE);
        }
        if (has(l, "country")) {
            return set(p.getCountry()) ? Answer.of(p.getCountry(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "^(location|city|current location|location \\(city\\)|which city)|city do you|where are you (currently )?(located|based)")) {
            return set(p.getCity()) ? Answer.of(p.getCity(), Source.PROFILE) : EMPTY;
        }
        // ---- History with this company ----
        if (has(l, "(previously|ever|currently) (been )?(work|employ|consult|contract)|worked (at|for)|employed by|former employee")
                && (company.isEmpty() || l.contains(company.split(" ")[0]) || has(l, "(us|our company)\\b"))) {
            return Answer.choose("No", Source.PROFILE, "\\bnever\\b", "^no\\b");
        }
        if (has(l, "employment agreement|non-?compete|post-employment|restrictive covenant")) {
            return Answer.choose("No", Source.PROFILE, "^no\\b");
        }
        // ---- Availability ----
        if (has(l, "available to start|start (full-time|working)|when (can|could|are) you (start|available)|earliest start|start date$|availability to start")) {
            return set(p.getFullTimeStart()) ? startAnswer(p.getFullTimeStart(), l) : EMPTY;
        }
        if (has(l, "intern") && has(l, "available|availability|full-time|6-month|six month|duration|months")) {
            return set(p.getInternshipAvailable()) ? yesNoAnswer(yes(p.getInternshipAvailable()), Source.PROFILE) : EMPTY;
        }
        if (has(l, "onsite|on-site|in-office|in office|work from (our|the) .*office|relocat|commute|hybrid")) {
            return set(p.getOnsiteOk()) ? yesNoAnswer(yes(p.getOnsiteOk()), Source.PROFILE) : EMPTY;
        }
        if (has(l, "notice period")) {
            return set(p.getNoticePeriod()) ? Answer.of(p.getNoticePeriod(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "offer deadline|other (offers|interviews)|recruiting-process deadline|competing offer")) {
            return set(p.getOfferDeadlines()) ? Answer.choose(p.getOfferDeadlines(), Source.PROFILE, "^no\\b") : EMPTY;
        }
        // ---- Education ----
        if (has(l, "^(school|university|college|institution)|school name|name of (your )?(university|college|school)|which (university|college)")) {
            return set(p.getSchool()) ? Answer.choose(p.getSchool(), Source.PROFILE, "^other$") : EMPTY;
        }
        if (has(l, "^degree|degree (type|level)|highest (degree|qualification|level of education)")) {
            return set(p.getDegree()) ? Answer.choose(p.getDegree(), Source.PROFILE, degreePattern(p.getDegree())) : EMPTY;
        }
        if (has(l, "discipline|major|field of study|branch|specialization|stream")) {
            return set(p.getDiscipline()) ? Answer.choose(p.getDiscipline(), Source.PROFILE, "computer science", "computer engineering",
                    "^engineering$") : EMPTY;
        }
        if (has(l, "start date month|start month")) {
            return EMPTY; // rarely asked as required; leave for you
        }
        if (has(l, "start date year|start year|year (you )?started")) {
            return set(p.getStartYear()) ? Answer.of(p.getStartYear(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "end date month|graduation month|month of graduation")) {
            return set(p.getGradMonth()) ? Answer.choose(p.getGradMonth(), Source.PROFILE, "^" + p.getGradMonth().substring(0, Math.min(3, p.getGradMonth().length()))) : EMPTY;
        }
        if (has(l, "end date year|graduation (year|date)|year of graduation|graduating year|when (do|will) you graduate|expected graduation|batch")) {
            return set(p.getGradYear()) ? Answer.of(p.getGradYear(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "\\bc?gpa\\b|grade point|cumulative grade|percentage|academic score")) {
            return set(p.getCgpa()) ? Answer.of(p.getCgpa(), Source.PROFILE) : EMPTY;
        }
        // ---- Work ----
        if (has(l, "(current|previous|most recent|last) (or previous )?(employer|company)|name of (your )?(current )?(employer|company)")) {
            return set(p.getCurrentCompany()) ? Answer.of(p.getCurrentCompany(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "job title|current (role|title|position)|previous (role|title)")) {
            return set(p.getCurrentTitle()) ? Answer.of(p.getCurrentTitle(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "years of (relevant |professional |total )?experience|how many years|total experience")) {
            return set(p.getYearsExperience()) ? Answer.choose(p.getYearsExperience(), Source.PROFILE, yearsPattern(p.getYearsExperience())) : EMPTY;
        }
        if (has(l, "(expected|desired|current) .*(salary|ctc|compensation|pay)|salary expectation")) {
            return set(p.getExpectedSalary()) ? Answer.of(p.getExpectedSalary(), Source.PROFILE) : EMPTY;
        }
        // ---- Skills and links ----
        if (has(l, "primary (programming )?language|programming language|tech(nology)? stack|framework")) {
            return set(p.getPrimaryLanguage()) ? Answer.of(p.getPrimaryLanguage(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "open source|open-source")) {
            return set(p.getOpenSourceLinks()) ? Answer.of(p.getOpenSourceLinks(), Source.PROFILE)
                    : set(p.getGithubUrl()) ? Answer.of(p.getGithubUrl(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "linkedin")) {
            // "LinkedIn Profile, Github, Personal Website, or Portfolio" → LinkedIn is the safest single answer
            return set(p.getLinkedinUrl()) ? Answer.of(p.getLinkedinUrl(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "github")) {
            return set(p.getGithubUrl()) ? Answer.of(p.getGithubUrl(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "portfolio|personal (web)?site|website")) {
            return set(p.getPortfolioUrl()) ? Answer.of(p.getPortfolioUrl(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "codeforces")) {
            return set(p.getCodeforcesRating()) ? Answer.of(p.getCodeforcesRating(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "codechef")) {
            return set(p.getCodechefRating()) ? Answer.of(p.getCodechefRating(), Source.PROFILE) : EMPTY;
        }
        // ---- About you ----
        if (has(l, "preferred (first )?name|name you'?d prefer|what should we call you")) {
            return Answer.of(set(p.getPreferredName()) ? p.getPreferredName() : p.getFirstName(), Source.PROFILE);
        }
        if (has(l, "pronoun")) {
            return set(p.getPronouns()) ? Answer.of(p.getPronouns(), Source.PROFILE) : EMPTY;
        }
        if (has(l, "how did you (hear|find|learn)|where did you (hear|find)|referral source|source of application")) {
            return set(p.getHowHeard()) ? Answer.choose(p.getHowHeard(), Source.PROFILE, "career|company website|job board|other") : EMPTY;
        }
        if (has(l, "whatsapp|sms|text message|marketing|newsletter|talent community|keep in touch|future opportunities")) {
            return Answer.choose("No", Source.PROFILE, "^no\\b", "opt.?out|do not");
        }
        return null;
    }

    /** Full-time start: the quarter/month you can start, and every later one. */
    private Answer startAnswer(String start, String label) {
        YearMonth from = parseMonth(start);
        if (from == null) {
            return Answer.of(start, Source.PROFILE); // "Immediately", "After graduation"…
        }
        List<Pattern> ps = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            YearMonth m = from.plusMonths(i * 3L);
            int q = (m.getMonthValue() - 1) / 3 + 1;
            ps.add(rx("q" + q + "\\s*" + m.getYear()));
        }
        String month = from.getMonth().getDisplayName(TextStyle.FULL, Locale.ENGLISH) + " " + from.getYear();
        ps.add(rx(from.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH).toLowerCase(Locale.ROOT) + "[a-z]*\\.? " + from.getYear()));
        return new Answer(month, ps, true, Source.PROFILE, null);
    }

    static YearMonth parseMonth(String s) {
        var m = Pattern.compile("(?i)(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*\\.?\\s*,?\\s*((19|20)\\d{2})").matcher(s);
        if (!m.find()) {
            return null;
        }
        int month = List.of("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
                .indexOf(m.group(1).toLowerCase(Locale.ROOT)) + 1;
        return YearMonth.of(Integer.parseInt(m.group(2)), month);
    }

    private boolean countryMatches(String l) {
        String c = norm(p.getCountry());
        String jobPlace = norm(job.getLocation());
        // "eligible to work in India?" or "…in the country where this role is based" (role in your country)
        return l.contains(c) || (has(l, "country where|this country|the country|where this role") && jobPlace.contains(c))
                || (has(l, "where this role|this location") && set(p.getCity()) && jobPlace.contains(norm(p.getCity())));
    }

    private static Answer yesNoAnswer(boolean yes, Source src) {
        return yes ? Answer.choose("Yes", src, "^yes\\b", "^i am\\b(?!.* not)", "^i (do|can|will)\\b(?!.* not)")
                : Answer.choose("No", src, "^no\\b", "\\b(do|am|will) not\\b", "\\bnever\\b");
    }

    static Boolean yesNo(String v) {
        String s = norm(v);
        return s.equals("yes") || s.equals("y") ? Boolean.TRUE : s.equals("no") || s.equals("n") ? Boolean.FALSE : null;
    }

    private static boolean yes(String v) {
        return Boolean.TRUE.equals(yesNo(v));
    }

    private static String degreePattern(String degree) {
        String d = norm(degree);
        if (d.matches(".*(b\\.?\\s?tech|b\\.?\\s?e\\b|bachelor|b\\.?\\s?sc|bca|b\\.?s\\b).*")) {
            return "bachelor";
        }
        if (d.matches(".*(m\\.?\\s?tech|master|m\\.?\\s?s\\b|mca|m\\.?\\s?sc).*")) {
            return "master";
        }
        return Pattern.quote(degree);
    }

    /** "0" → option "0-1 years" / "Less than 1 year" / "0". */
    private static String yearsPattern(String years) {
        String y = years.replaceAll("[^0-9.]", "");
        if (y.isEmpty()) {
            return Pattern.quote(years);
        }
        double n = Double.parseDouble(y);
        if (n < 1) {
            return "^(0|less than 1|< ?1|0 ?- ?1|0 ?to ?1|none|no experience|fresher)";
        }
        return "^" + (int) n + "\\b";
    }

    /** "India" also matches the option "India +91". */
    private static Pattern startsWith(String text) {
        return Pattern.compile("^\\s*" + Pattern.quote(text == null ? "" : text.strip()) + "\\b", Pattern.CASE_INSENSITIVE);
    }

    private static Pattern exact(String text) {
        return Pattern.compile("^\\s*" + Pattern.quote(text == null ? "" : text.strip()) + "\\s*$", Pattern.CASE_INSENSITIVE);
    }

    private static Pattern rx(String s) {
        return Pattern.compile(s, Pattern.CASE_INSENSITIVE);
    }

    private static boolean has(String l, String regex) {
        return Pattern.compile(regex, Pattern.CASE_INSENSITIVE).matcher(l).find();
    }

    private static boolean set(String v) {
        return v != null && !v.isBlank();
    }

    static String norm(String s) {
        return s == null ? "" : s.replace(' ', ' ').replace("*", "").replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }
}
