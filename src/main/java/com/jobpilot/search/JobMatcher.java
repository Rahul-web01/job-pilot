package com.jobpilot.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.jobpilot.profile.Profile;
import com.jobpilot.source.JobPosting;

/**
 * Free keyword matcher. Score 0-100:
 * title match 30 + your skills found in the description up to 50 + preferred location 20,
 * then adjusted for your experience level (Autopilot tab): a fresher gets entry-level roles boosted
 * and senior roles dropped; an experienced person gets roles that fit their years.
 */
@Component
public class JobMatcher {

    public record Match(boolean keep, int score, List<String> matchedSkills) {
    }

    /** Titles above entry level. */
    static final Pattern SENIOR = Pattern.compile("\\b(senior|sr\\.?|staff|principal|lead|manager|director|head of|architect|vp|"
            + "chief|distinguished)\\b|\\b(engineer|developer|sde|swe)\\s*-?\\s*(ii|iii|iv|v|2|3|4|5)\\b|\\bintermediate\\b|"
            + "mid[- ]?level|\\b[lL][4-8]\\b", Pattern.CASE_INSENSITIVE);
    /** Titles meant for students and new graduates. */
    static final Pattern STUDENT = Pattern.compile("\\b(intern|internship|new grad|graduate|campus|university|trainee|apprentice|"
            + "fresher|early career|co-?op)\\b", Pattern.CASE_INSENSITIVE);
    /** Other entry-level titles. */
    static final Pattern JUNIOR = Pattern.compile("\\b(junior|jr\\.?|entry[- ]level|associate)\\b|\\b(engineer|developer|sde|swe)"
            + "\\s*-?\\s*(i|1)\\b", Pattern.CASE_INSENSITIVE);
    /** "3+ years of experience", "2-4 yrs experience", "minimum 5 years in backend…". */
    private static final Pattern YEARS = Pattern.compile("(\\d{1,2})\\s*\\+?\\s*(?:(?:-|–|to)\\s*\\d{1,2}\\s*)?\\+?\\s*(?:years?|yrs?)"
            + "\\b([^.\\n]{0,60})", Pattern.CASE_INSENSITIVE);

    public Match evaluate(JobPosting p, Profile profile) {
        List<String> titles = Profile.lines(profile.getTargetTitles());
        List<String> skills = Profile.lines(profile.getSkills());
        List<String> exclude = Profile.lines(profile.getExcludeTitleWords());
        List<String> locations = Profile.lines(profile.getLocations());

        String title = lower(p.title());
        if (title.isEmpty() || !titleMatches(title, titles) || containsAny(title, exclude)) {
            return new Match(false, 0, List.of());
        }

        String text = title + "\n" + lower(p.description());
        List<String> matched = new ArrayList<>();
        for (String skill : skills) {
            if (containsWord(text, lower(skill))) {
                matched.add(skill);
            }
        }

        int skillScore = skills.isEmpty() ? 0
                // having ~60% of your skills in the description already counts as a full match
                : (int) Math.round(Math.min(1.0, matched.size() / (skills.size() * 0.6)) * 50);
        int locationScore = locationMatches(p.location(), locations) ? 20 : 0;
        int score = 30 + skillScore + locationScore;

        Integer fit = experienceFit(p.title(), p.description(), profile);
        if (fit == null) {
            return new Match(false, 0, List.of());
        }
        return new Match(true, Math.max(0, Math.min(100, score + fit)), matched);
    }

    /**
     * Score change for your experience level, or null if the job doesn't fit it at all.
     * Without a level set, nothing changes.
     */
    static Integer experienceFit(String title, String description, Profile profile) {
        String level = profile.getExperienceLevel();
        if (level == null || level.isBlank()) {
            return 0;
        }
        double years = parseYears(profile.getYearsExperience());
        Integer required = yearsRequired(description);
        boolean senior = SENIOR.matcher(nz(title)).find();
        boolean student = STUDENT.matcher(nz(title)).find();
        boolean junior = JUNIOR.matcher(nz(title)).find();

        if ("FRESHER".equals(level)) {
            if (senior) {
                return null;
            }
            if (required != null && required >= 3) {
                return null;
            }
            int fit = 0;
            if (student || junior) {
                fit += 25;
            }
            if (required != null && required == 2) {
                fit -= 15;
            }
            return fit;
        }
        // Experienced
        if (student) {
            return null;
        }
        if (required != null && required > years + 2) {
            return null;
        }
        if (senior && years < 3) {
            return null;
        }
        int fit = 0;
        if (required != null && required <= years) {
            fit += 10;
        }
        if (junior && years >= 3) {
            fit -= 15;
        }
        return fit;
    }

    /** The smallest "N years … experience" the description asks for, or null if it doesn't say. */
    static Integer yearsRequired(String description) {
        if (description == null) {
            return null;
        }
        Integer min = null;
        Matcher m = YEARS.matcher(description);
        while (m.find()) {
            String after = m.group(2).toLowerCase(Locale.ROOT);
            if (!after.contains("experience") && !after.matches("\\s*(in|of|working|building|developing|with|as|hands-on).*")) {
                continue;
            }
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= 20 && (min == null || n < min)) {
                min = n;
            }
        }
        return min;
    }

    static double parseYears(String s) {
        if (s == null) {
            return 0;
        }
        String digits = s.replaceAll("[^0-9.]", "");
        try {
            return digits.isEmpty() ? 0 : Double.parseDouble(digits);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Places outside India that "Remote - …" jobs are often limited to. */
    private static final Pattern FOREIGN = Pattern.compile("\\b(usa|us|u\\.s\\.|united states|uk|united kingdom|canada|europe|eu|emea|"
            + "americas?|amer|latam|north america|germany|ireland|netherlands|spain|france|poland|portugal|romania|singapore|"
            + "australia|japan|brazil|mexico|macedonia|israel|uae|dubai)\\b", Pattern.CASE_INSENSITIVE);

    /**
     * True if the job is somewhere you want to work. "Remote" only counts when the job doesn't limit it to another
     * country, so "Remote - USA" is not a match for someone in India who listed "remote".
     */
    public static boolean locationMatches(String location, List<String> preferred) {
        String loc = lower(location);
        boolean other = false;
        for (String want : preferred) {
            String w = lower(want).strip();
            if (w.isEmpty() || !containsWord(loc, w)) {
                continue;
            }
            if (!w.equals("remote") && !w.equals("anywhere")) {
                return true; // a real place you listed
            }
            other = true;
        }
        return other && !FOREIGN.matcher(loc).find();
    }

    /** Words that say the level, not the job: "intern" alone would also match "Product Management Intern". */
    private static final java.util.Set<String> LEVEL_WORDS = java.util.Set.of("intern", "internship", "graduate", "new grad",
            "trainee", "fresher", "junior", "associate", "entry level", "entry-level", "apprentice", "campus");

    /**
     * The title must contain one of your role titles. Level words (intern, graduate, junior…) only count
     * together with a role title, so a Java intern search doesn't pick up design or finance internships.
     */
    static boolean titleMatches(String title, List<String> targets) {
        List<String> roles = targets.stream().filter(t -> !LEVEL_WORDS.contains(lower(t).strip())).toList();
        if (roles.isEmpty()) {
            return containsAny(title, targets);
        }
        return containsAny(title, roles);
    }

    private static boolean containsAny(String haystack, List<String> needles) {
        for (String n : needles) {
            if (containsWord(haystack, lower(n))) {
                return true;
            }
        }
        return false;
    }

    /** Whole-word match, so "java" does not match "javascript". */
    static boolean containsWord(String haystack, String word) {
        if (word.isBlank()) {
            return false;
        }
        return Pattern.compile("(?<![a-z0-9])" + Pattern.quote(word) + "(?![a-z0-9])").matcher(haystack).find();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
