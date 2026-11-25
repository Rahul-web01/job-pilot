package com.jobpilot.apply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

import com.jobpilot.job.Job;
import com.jobpilot.profile.Profile;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.FilePayload;
import com.microsoft.playwright.options.SelectOption;

/**
 * Fills a job application form that is already open in a Playwright page:
 * your details, resume, cover letter, and then every question it can answer from your Profile
 * (text boxes, dropdowns, multi-choice, radio buttons). Open questions it doesn't recognise can be
 * drafted by the local AI from your resume. It never clicks Submit and never answers legal agreements.
 */
public class FormFiller {

    /**
     * filled: what was filled (details, files, answered questions).
     * aiWritten: questions the local AI answered, so you can read them first.
     * needsYou: required questions still empty.
     * legal: agreements and declarations left for you on purpose.
     */
    public record Result(List<String> filled, List<String> needsYou, List<String> aiWritten, List<String> legal) {
        public Result(List<String> filled, List<String> needsYou) {
            this(filled, needsYou, List.of(), List.of());
        }
    }

    /** A resume or cover letter to upload, with the file name the recruiter will see. */
    public record Upload(Path path, String fileName) {
    }

    /** One question found on the page. */
    record Field(String key, String kind, String label, boolean required, boolean filled, List<String> options) {
    }

    private final Page page;
    private final Profile p;
    private final Job job;
    /** (question, kind) → AI-written answer or null. May be null (no AI). */
    private final BiFunction<String, String, String> ai;
    private final List<String> filled = new ArrayList<>();
    private final List<String> aiWritten = new ArrayList<>();
    private final List<String> legal = new ArrayList<>();

    public FormFiller(Page page, Profile profile) {
        this(page, profile, null, null);
    }

    public FormFiller(Page page, Profile profile, Job job, BiFunction<String, String, String> ai) {
        this.page = page;
        this.p = profile;
        this.job = job != null ? job : new Job();
        this.ai = ai;
    }

    public Result fill(Upload resume, Upload coverLetter, String coverLetterText) {
        page.setDefaultTimeout(8000); // one stuck field must not stall the whole form
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

        // 2. Other basic text boxes, matched by their label
        Map<Pattern, String[]> byLabel = new LinkedHashMap<>();
        byLabel.put(rx("^first name|given name"), new String[] { p.getFirstName(), "First name" });
        byLabel.put(rx("^last name|surname|family name"), new String[] { p.getLastName(), "Last name" });
        byLabel.put(rx("^(full )?name\\b"), new String[] { p.getFullName(), "Full name" });
        byLabel.put(rx("e-?mail"), new String[] { p.getEmail(), "Email" });
        byLabel.put(rx("phone|mobile"), new String[] { p.getPhone(), "Phone" });
        for (var e : byLabel.entrySet()) {
            byLabel(e.getKey(), e.getValue()[0], e.getValue()[1]);
        }

        // 3. Files
        upload(resume, coverLetter);

        // 4. Cover letter as text (Lever "Additional information")
        if (coverLetterText != null && !coverLetterText.isBlank()) {
            byCss("textarea[name='comments']", coverLetterText, "Cover letter text");
        }

        // 5. Every other question on the form
        answerQuestions();

        List<String> needsYou = new ArrayList<>();
        for (Field f : discover()) {
            if (f.required() && !f.filled() && !legal.contains(f.label())) {
                needsYou.add(f.label());
            }
        }
        return new Result(List.copyOf(filled), needsYou, List.copyOf(aiWritten), List.copyOf(legal));
    }

    private void answerQuestions() {
        QuestionAnswerer answerer = new QuestionAnswerer(p, job);
        for (Field f : discover()) {
            if (f.filled() || f.label().isBlank()) {
                continue;
            }
            var a = answerer.answer(f.label());
            if (!a.answered()) {
                if (a.skip() == QuestionAnswerer.Skip.LEGAL && f.required()) {
                    legal.add(f.label());
                } else if (a.skip() == QuestionAnswerer.Skip.UNKNOWN && f.required() && ai != null
                        && (f.kind().equals("text") || f.kind().equals("textarea")) && openQuestion(f.label())) {
                    String text = ai.apply(f.label(), f.kind());
                    if (text != null && !text.isBlank() && fillText(f, text.strip())) {
                        aiWritten.add(short60(f.label()));
                        note(short60(f.label()) + " (AI)");
                    }
                }
                continue;
            }
            boolean ok = switch (f.kind()) {
                case "text", "textarea" -> fillText(f, a.text());
                case "select" -> chooseNative(f, a);
                case "combobox" -> chooseCombobox(f, a);
                case "radio", "checkbox" -> chooseTick(f, a);
                default -> false;
            };
            if (ok) {
                note(short60(f.label()));
            }
        }
    }

    /** Questions that ask for a written answer ("Why…", "Describe…"), not a fact like a rating or a yes/no. */
    static boolean openQuestion(String label) {
        String l = QuestionAnswerer.norm(label);
        if (l.matches("^(are|do|does|did|will|would|can|could|have|has|is) you\\b.*")) {
            return false;
        }
        return l.matches(".*\\b(why|describe|tell us|explain|share|what (excites|interests|motivates|attracts|makes)|"
                + "how (would|do|did|have) you|about yourself|about you|past internship|previous internship|"
                + "a project|your experience with|additional information|anything else|cover letter|motivation).*");
    }

    // ---- Filling each kind of field ----

    private boolean fillText(Field f, String text) {
        try {
            Locator el = page.locator(f.key()).first();
            if (!el.isVisible() || !el.isEditable()) {
                return false;
            }
            el.fill(text);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    private boolean chooseNative(Field f, QuestionAnswerer.Answer a) {
        String best = best(f.options(), a);
        if (best == null) {
            return false;
        }
        try {
            page.locator(f.key()).first().selectOption(new SelectOption().setLabel(best));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** React-style dropdowns (Greenhouse): click, read the options, pick; type to search long lists. */
    private boolean chooseCombobox(Field f, QuestionAnswerer.Answer a) {
        String step = "open";
        try {
            Locator input = page.locator(f.key()).first();
            input.scrollIntoViewIfNeeded();
            input.click();
            page.waitForTimeout(400);
            Locator menu = menuOf(input);
            List<String> options = texts(menu);
            for (int wait = 0; wait < 4 && options.stream().allMatch(String::isEmpty); wait++) {
                page.waitForTimeout(500); // some lists (degrees) load just after opening
                menu = menuOf(input);
                options = texts(menu);
            }
            List<String> picks = a.many() ? all(options, a) : listOf(best(options, a));
            if (picks.isEmpty() && a.text() != null) {
                // long lists (countries, schools): type to search
                step = "type";
                input.pressSequentially(searchText(a.text()), new Locator.PressSequentiallyOptions().setDelay(40));
                page.waitForTimeout(1500);
                menu = menuOf(input);
                options = texts(menu);
                picks = a.many() ? all(options, a) : listOf(best(options, a));
            }
            if (picks.isEmpty() && a.prefer().stream().anyMatch(pt -> pt.pattern().equals("^other$"))) {
                // e.g. your school isn't in their list: choose "Other"
                step = "type Other";
                input.fill("");
                input.pressSequentially("Other", new Locator.PressSequentiallyOptions().setDelay(40));
                page.waitForTimeout(1500);
                menu = menuOf(input);
                options = texts(menu);
                picks = listOf(best(options, a));
            }
            if (Boolean.getBoolean("jobpilot.debug")) {
                System.out.println("DEBUG combobox " + f.label() + " options=" + options.size() + " first=" + options.stream().limit(4).toList() + " picks=" + picks);
            }
            if (picks.isEmpty()) {
                step = "close";
                input.press("Escape");
                input.fill("");
                return false;
            }
            step = "pick " + picks;
            for (int i = 0; i < picks.size(); i++) {
                if (i > 0) {
                    input.click();
                    page.waitForTimeout(300);
                    menu = menuOf(input);
                    options = texts(menu);
                }
                int at = options.indexOf(picks.get(i));
                if (at < 0) {
                    continue;
                }
                menu.nth(at).click();
                page.waitForTimeout(250);
            }
            if (a.many()) {
                input.press("Escape");
            }
            return true;
        } catch (RuntimeException e) {
            if (Boolean.getBoolean("jobpilot.debug")) {
                System.out.println("DEBUG combobox " + f.label() + " failed at [" + step + "]: " + e.getMessage().lines().skip(1).findFirst().orElse(""));
            }
            try {
                page.keyboard().press("Escape");
            } catch (RuntimeException ignored) {
                // nothing to close
            }
            return false;
        }
    }

    private boolean chooseTick(Field f, QuestionAnswerer.Answer a) {
        List<String> picks = a.many() || f.kind().equals("checkbox") && f.options().size() > 1 ? all(f.options(), a)
                : listOf(best(f.options(), a));
        if (picks.isEmpty()) {
            return false;
        }
        try {
            Locator boxes = page.locator(f.key());
            for (String pick : picks) {
                int i = f.options().indexOf(pick);
                if (i >= 0) {
                    boxes.nth(i).check(new Locator.CheckOptions().setForce(true));
                }
            }
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /**
     * The options of the menu this dropdown opened. Scoped to its own listbox (aria-controls), because pages
     * also keep hidden option lists around, e.g. the phone-number country picker with 245 entries.
     */
    private Locator menuOf(Locator input) {
        String listbox = input.getAttribute("aria-controls");
        if (listbox != null && !listbox.isBlank() && page.locator("[id='" + listbox.replace("'", "\\'") + "']").count() > 0) {
            return page.locator("[id='" + listbox.replace("'", "\\'") + "'] [role=option]");
        }
        return page.locator("[role=option]:visible");
    }

    /** Option texts in order; empty text and "No options" become "" so indexes still match the locator. */
    private static List<String> texts(Locator opts) {
        // one snapshot: lists that load as you type change between separate reads
        List<String> out = new ArrayList<>();
        for (String raw : opts.allInnerTexts()) {
            String t = raw.strip();
            out.add(t.isEmpty() || t.equalsIgnoreCase("no options") || t.toLowerCase().startsWith("loading") ? "" : t);
        }
        return out.size() > 300 ? out.subList(0, 300) : out;
    }

    /** The first option matching the answer's patterns, in the patterns' order. */
    static String best(List<String> options, QuestionAnswerer.Answer a) {
        for (Pattern pt : a.prefer()) {
            for (String o : options) {
                if (pt.matcher(o.strip()).find()) {
                    return o;
                }
            }
        }
        return null;
    }

    static List<String> all(List<String> options, QuestionAnswerer.Answer a) {
        List<String> out = new ArrayList<>();
        for (String o : options) {
            if (a.prefer().stream().anyMatch(pt -> pt.matcher(o.strip()).find())) {
                out.add(o);
            }
        }
        return out;
    }

    /** What to type into a searchable dropdown: the first words are enough and survive small spelling differences. */
    static String searchText(String text) {
        String[] words = text.strip().split("\\s+");
        return words.length <= 2 ? text.strip() : words[0] + " " + words[1];
    }

    // ---- Finding the questions ----

    @SuppressWarnings("unchecked")
    List<Field> discover() {
        Object res = page.evaluate("""
                () => {
                  const out = [], seen = new Set();
                  const clean = s => (s || '').replace(/[\\*✱]/g, '').replace(/\\s+/g, ' ').trim();
                  const labelOf = e => {
                    let t = '';
                    if (e.id) { const l = document.querySelector(`label[for="${CSS.escape(e.id)}"]`); if (l) t = l.innerText; }
                    if (!t && e.getAttribute('aria-labelledby'))
                      t = e.getAttribute('aria-labelledby').split(' ').map(i => document.getElementById(i)?.innerText || '').join(' ');
                    if (!t) t = e.getAttribute('aria-label') || '';
                    if (!t) t = e.closest('fieldset')?.querySelector('legend')?.innerText || '';
                    if (!t) t = e.closest('.application-question')?.querySelector('.application-label, .text')?.innerText || '';
                    if (!t) t = e.closest('label')?.innerText || '';
                    return t;
                  };
                  const filledCombo = e => {
                    let c = e;
                    for (let i = 0; i < 6 && c; i++) {
                      c = c.parentElement;
                      if (c && c.querySelector('[class*="single-value"],[class*="singleValue"],[class*="multi-value"],[class*="multiValue"]')) return true;
                    }
                    return false;
                  };
                  for (const e of document.querySelectorAll('input, textarea, select')) {
                    const type = (e.type || '').toLowerCase();
                    if (['hidden', 'file', 'submit', 'button', 'search', 'image', 'reset'].includes(type)) continue;
                    if (e.name === 'g-recaptcha-response' || (!e.id && !e.name)) continue;
                    if (e.closest('[aria-hidden="true"]') || e.closest('.iti__country-container')) continue;
                    let kind, key, filled, options = [];
                    if (type === 'radio' || type === 'checkbox') {
                      if (!e.name || seen.has('n:' + e.name)) continue;
                      seen.add('n:' + e.name);
                      key = `input[name="${CSS.escape(e.name)}"]`;
                      const group = [...document.querySelectorAll(key)];
                      kind = type;
                      filled = group.some(g => g.checked);
                      options = group.map(g => clean((g.id && document.querySelector(`label[for="${CSS.escape(g.id)}"]`)?.innerText)
                                                     || g.closest('label')?.innerText || g.value));
                      let label = clean(e.closest('fieldset')?.querySelector('legend')?.innerText
                                        || e.closest('.application-question')?.querySelector('.application-label, .text')?.innerText
                                        || (group.length === 1 ? labelOf(e) : ''));
                      const required = group.some(g => g.required || g.getAttribute('aria-required') === 'true')
                                       || /\\*|✱/.test(e.closest('fieldset, .application-question')?.innerText?.split('\\n')[0] || '');
                      out.push({ key, kind, label, required, filled, options });
                      continue;
                    }
                    if (!e.id && seen.has('n:' + e.name)) continue;
                    key = e.id ? `#${CSS.escape(e.id)}` : `[name="${CSS.escape(e.name)}"]`;
                    if (seen.has(key)) continue;
                    seen.add(key);
                    const rawLabel = labelOf(e);
                    const label = clean(rawLabel);
                    const required = e.required || e.getAttribute('aria-required') === 'true' || /[\\*✱]/.test(rawLabel);
                    if (e.tagName === 'SELECT') {
                      kind = 'select';
                      options = [...e.options].map(o => clean(o.text)).filter(t => t && !/^(select|choose|--|please select)/i.test(t));
                      filled = e.selectedIndex > 0 && !!e.value;
                    } else if (e.getAttribute('role') === 'combobox' || (e.className || '').toString().includes('select__input')) {
                      kind = 'combobox';
                      filled = filledCombo(e);
                    } else {
                      kind = e.tagName === 'TEXTAREA' ? 'textarea' : 'text';
                      filled = !!(e.value && e.value.trim());
                    }
                    out.push({ key, kind, label, required, filled, options });
                  }
                  return out;
                }
                """);
        List<Field> fields = new ArrayList<>();
        if (res instanceof List<?> list) {
            for (Object o : list) {
                Map<String, Object> m = (Map<String, Object>) o;
                fields.add(new Field((String) m.get("key"), (String) m.get("kind"), String.valueOf(m.get("label")),
                        Boolean.TRUE.equals(m.get("required")), Boolean.TRUE.equals(m.get("filled")),
                        (List<String>) m.getOrDefault("options", List.of())));
            }
        }
        return fields;
    }

    // ---- Details and files ----

    private void byCss(String css, String value, String what) {
        if (blank(value)) {
            return;
        }
        Locator all = page.locator(css);
        for (int i = 0; i < all.count(); i++) {
            if (fillIfEmpty(all.nth(i), value)) {
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
            String role = (String) el.evaluate("e => e.getAttribute('role') || ''");
            boolean textLike = !role.equals("combobox") && (tag.equals("textarea") || (tag.equals("input")
                    && List.of("text", "email", "tel", "url", "number", "").contains(type)));
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
            boolean isResume = hint.contains("resume") || hint.contains("cv");
            if (!isCover && !isResume) {
                continue; // e.g. "academic record / transcript": not something we have
            }
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

    private void note(String what) {
        if (!filled.contains(what)) {
            filled.add(what);
        }
    }

    private static List<String> listOf(String s) {
        return s == null ? List.of() : List.of(s);
    }

    private static String short60(String s) {
        return s.length() <= 60 ? s : s.substring(0, 57) + "…";
    }

    private static Pattern rx(String s) {
        return Pattern.compile(s, Pattern.CASE_INSENSITIVE);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
