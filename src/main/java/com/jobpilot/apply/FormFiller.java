package com.jobpilot.apply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import com.jobpilot.profile.Profile;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;

/**
 * Fills a job application form that is already open in a Playwright page.
 * It never clicks Submit: you review the form and submit it yourself.
 */
public class FormFiller {

    public record Result(List<String> filled, List<String> needsYou) {
    }

    /** A resume or cover letter to upload, with the file name the recruiter will see. */
    public record Upload(Path path, String fileName) {
    }

    private final Page page;
    private final Profile p;
    private final List<String> filled = new ArrayList<>();

    public FormFiller(Page page, Profile profile) {
        this.page = page;
        this.p = profile;
    }

    public Result fill(Upload resume, Upload coverLetter, String coverLetterText) {
        // 1. Known field names on Greenhouse and Lever forms
        byCss("#first_name, input[name='job_application[first_name]']", p.getFirstName(), "First name");
        byCss("#last_name, input[name='job_application[last_name]']", p.getLastName(), "Last name");
        byCss("#email, input[name='email'], input[name='job_application[email]']", p.getEmail(), "Email");
        byCss("#phone, input[name='phone'], input[name='job_application[phone]']", p.getPhone(), "Phone");
        byCss("input[name='name']", p.getFullName(), "Full name");
        byCss("input[name='org']", p.getCurrentCompany(), "Current company");
        byCss("input[name='location']", p.getCity(), "Location");
        byCss("input[name='urls[LinkedIn]']", p.getLinkedinUrl(), "LinkedIn");
        byCss("input[name='urls[GitHub]']", p.getGithubUrl(), "GitHub");
        byCss("input[name='urls[Portfolio]'], input[name='urls[Other]']", p.getPortfolioUrl(), "Portfolio");

        // 2. Any other text box, matched by its label
        Map<Pattern, String[]> byLabel = new LinkedHashMap<>();
        byLabel.put(rx("^first name|given name"), new String[] { p.getFirstName(), "First name" });
        byLabel.put(rx("^last name|surname|family name"), new String[] { p.getLastName(), "Last name" });
        byLabel.put(rx("^(full )?name\\b"), new String[] { p.getFullName(), "Full name" });
        byLabel.put(rx("e-?mail"), new String[] { p.getEmail(), "Email" });
        byLabel.put(rx("phone|mobile"), new String[] { p.getPhone(), "Phone" });
        byLabel.put(rx("linkedin"), new String[] { p.getLinkedinUrl(), "LinkedIn" });
        byLabel.put(rx("github"), new String[] { p.getGithubUrl(), "GitHub" });
        byLabel.put(rx("portfolio|website|personal site"), new String[] { p.getPortfolioUrl(), "Portfolio" });
        byLabel.put(rx("current (company|employer)"), new String[] { p.getCurrentCompany(), "Current company" });
        byLabel.put(rx("^(current )?(location|city)"), new String[] { p.getCity(), "Location" });
        byLabel.put(rx("notice period"), new String[] { p.getNoticePeriod(), "Notice period" });
        byLabel.put(rx("(expected|desired).*(salary|ctc|compensation)"), new String[] { p.getExpectedSalary(), "Expected salary" });
        byLabel.put(rx("years of (relevant )?experience|total experience"), new String[] { p.getYearsExperience(), "Years of experience" });
        for (var e : byLabel.entrySet()) {
            byLabel(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }

        // 3. Files
        upload(resume, coverLetter);

        // 4. Cover letter as text (Lever "Additional information", Greenhouse "type it in")
        if (coverLetterText != null && !coverLetterText.isBlank()) {
            byCss("textarea[name='comments'], textarea#cover_letter_text", coverLetterText, "Cover letter text");
        }

        return new Result(List.copyOf(filled), needsYou());
    }

    private void byCss(String css, String value, String what) {
        if (blank(value)) {
            return;
        }
        Locator all = page.locator(css);
        for (int i = 0; i < all.count(); i++) {
            Locator el = all.nth(i);
            if (fillIfEmpty(el, value)) {
                note(what);
                return;
            }
        }
    }

    private void byLabel(Pattern label, String value, String what) {
        if (blank(value)) {
            return;
        }
        Locator all = page.getByLabel(label);
        for (int i = 0; i < all.count(); i++) {
            Locator el = all.nth(i);
            String tag = (String) el.evaluate("e => e.tagName.toLowerCase()");
            String type = (String) el.evaluate("e => (e.type || '').toLowerCase()");
            boolean textLike = tag.equals("textarea") || (tag.equals("input")
                    && List.of("text", "email", "tel", "url", "number", "").contains(type));
            if (textLike && fillIfEmpty(el, value)) {
                note(what);
            }
        }
    }

    private static boolean fillIfEmpty(Locator el, String value) {
        try {
            if (!el.isVisible() || !el.isEditable() || !el.inputValue().isBlank()) {
                return false;
            }
            el.fill(value);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void upload(Upload resume, Upload coverLetter) {
        List<ElementHandle> inputs = page.querySelectorAll("input[type=file]");
        for (ElementHandle input : inputs) {
            String hint = String.valueOf(input.evaluate(
                    "e => [e.id, e.name, e.getAttribute('aria-label'), e.getAttribute('aria-labelledby') && "
                            + "document.getElementById(e.getAttribute('aria-labelledby'))?.innerText, "
                            + "e.closest('fieldset,div[class*=field],div[class*=upload],label')?.innerText]"
                            + ".filter(Boolean).join(' ').toLowerCase()"));
            boolean isCover = hint.contains("cover");
            Upload file = isCover ? coverLetter : resume;
            if (file == null || !Files.exists(file.path())) {
                continue;
            }
            try {
                input.setInputFiles(new FilePayload(file.fileName(), "application/pdf", Files.readAllBytes(file.path())));
                note(isCover ? "Cover letter PDF" : "Resume PDF");
            } catch (Exception ignored) {
                // some sites hide inputs that are not meant to be used; skip them
            }
        }
    }

    /** Required fields that are still empty after filling: the custom questions only you can answer. */
    @SuppressWarnings("unchecked")
    private List<String> needsYou() {
        Object res = page.evaluate("""
                () => {
                  const out = [];
                  const els = document.querySelectorAll('input, textarea, select');
                  for (const e of els) {
                    const required = e.required || e.getAttribute('aria-required') === 'true';
                    if (!required || e.type === 'hidden' || e.type === 'file' || e.type === 'submit') continue;
                    if ((e.type === 'checkbox' || e.type === 'radio')) {
                      if (document.querySelector(`input[name="${CSS.escape(e.name)}"]:checked`)) continue;
                    } else if (e.value && e.value.trim()) continue;
                    let label = '';
                    if (e.id) { const l = document.querySelector(`label[for="${CSS.escape(e.id)}"]`); if (l) label = l.innerText; }
                    if (!label) label = e.getAttribute('aria-label') || e.closest('label')?.innerText || e.name || e.id || 'Unnamed field';
                    label = label.replace(/\\*/g, '').replace(/\\s+/g, ' ').trim();
                    if (!out.includes(label)) out.push(label);
                  }
                  return out;
                }
                """);
        return res instanceof List<?> l ? (List<String>) l : List.of();
    }

    private void note(String what) {
        if (!filled.contains(what)) {
            filled.add(what);
        }
    }

    private static Pattern rx(String s) {
        return Pattern.compile(s, Pattern.CASE_INSENSITIVE);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
