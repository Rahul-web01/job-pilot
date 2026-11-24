package com.jobpilot.search;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.jobpilot.profile.Profile;
import com.jobpilot.source.JobPosting;

/**
 * Free keyword matcher. Score 0-100:
 * title match 30 + your skills found in the description up to 50 + preferred location 20.
 */
@Component
public class JobMatcher {

    public record Match(boolean keep, int score, List<String> matchedSkills) {
    }

    public Match evaluate(JobPosting p, Profile profile) {
        List<String> titles = Profile.lines(profile.getTargetTitles());
        List<String> skills = Profile.lines(profile.getSkills());
        List<String> exclude = Profile.lines(profile.getExcludeTitleWords());
        List<String> locations = Profile.lines(profile.getLocations());

        String title = lower(p.title());
        if (title.isEmpty() || !containsAny(title, titles) || containsAny(title, exclude)) {
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
        int locationScore = containsAny(lower(p.location()), locations) ? 20 : 0;

        return new Match(true, 30 + skillScore + locationScore, matched);
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

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
