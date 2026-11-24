package com.jobpilot.profile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Locale;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.jobpilot.config.JobPilotProperties;

@Service
public class ProfileService {

    private final ProfileRepository repo;
    private final JobPilotProperties props;

    public ProfileService(ProfileRepository repo, JobPilotProperties props) {
        this.repo = repo;
        this.props = props;
    }

    /** The profile, created from application.yml defaults on first use. */
    @Transactional
    public Profile get() {
        return repo.findById(Profile.ID).orElseGet(() -> repo.save(defaults()));
    }

    @Transactional
    public Profile save(Profile form) {
        Profile p = get();
        p.setFirstName(clean(form.getFirstName()));
        p.setLastName(clean(form.getLastName()));
        p.setEmail(clean(form.getEmail()));
        p.setPhone(clean(form.getPhone()));
        p.setCity(clean(form.getCity()));
        p.setLinkedinUrl(clean(form.getLinkedinUrl()));
        p.setGithubUrl(clean(form.getGithubUrl()));
        p.setPortfolioUrl(clean(form.getPortfolioUrl()));
        p.setCurrentCompany(clean(form.getCurrentCompany()));
        p.setYearsExperience(clean(form.getYearsExperience()));
        p.setNoticePeriod(clean(form.getNoticePeriod()));
        p.setExpectedSalary(clean(form.getExpectedSalary()));
        p.setWorkAuthorization(clean(form.getWorkAuthorization()));
        p.setTargetTitles(normalizeList(form.getTargetTitles()));
        p.setSkills(normalizeList(form.getSkills()));
        p.setExcludeTitleWords(normalizeList(form.getExcludeTitleWords()));
        p.setLocations(normalizeList(form.getLocations()));
        p.setGreenhouseBoards(normalizeSlugs(form.getGreenhouseBoards()));
        p.setLeverCompanies(normalizeSlugs(form.getLeverCompanies()));
        p.setRemotiveSearches(normalizeList(form.getRemotiveSearches()));
        p.setOllamaUrl(blankTo(clean(form.getOllamaUrl()), props.ollama().url()));
        p.setOllamaModel(blankTo(clean(form.getOllamaModel()), props.ollama().model()));
        if (form.getResumeText() != null) {
            p.setResumeText(form.getResumeText().strip());
        }
        return repo.save(p);
    }

    /** Stores the uploaded PDF in ./data/uploads and extracts its text. */
    @Transactional
    public Profile uploadResume(MultipartFile file) throws IOException {
        String name = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String lower = name.toLowerCase(Locale.ROOT);
        byte[] bytes = file.getBytes();
        String text;
        if (lower.endsWith(".pdf")) {
            try (var doc = Loader.loadPDF(bytes)) {
                text = new PDFTextStripper().getText(doc);
            }
        } else if (lower.endsWith(".txt") || lower.endsWith(".md")) {
            text = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        } else {
            throw new IllegalArgumentException("Please upload a PDF or .txt resume.");
        }
        Path dir = uploadsDir();
        Files.createDirectories(dir);
        Files.write(dir.resolve(lower.endsWith(".pdf") ? "resume.pdf" : "resume.txt"), bytes);

        Profile p = get();
        p.setResumeFileName(name);
        p.setResumeUploadedAt(Instant.now());
        p.setResumeText(text.replace("\r", "").replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip());
        return repo.save(p);
    }

    /** The original uploaded resume PDF, if there is one. */
    public Path originalResumePdf() {
        Path pdf = uploadsDir().resolve("resume.pdf");
        return Files.exists(pdf) ? pdf : null;
    }

    public Path dataDir() {
        return Path.of(props.dataDir()).toAbsolutePath().normalize();
    }

    private Path uploadsDir() {
        return dataDir().resolve("uploads");
    }

    private Profile defaults() {
        Profile p = new Profile();
        var d = props.profile();
        var s = props.sources();
        p.setTargetTitles(Profile.join(d.targetTitles()));
        p.setSkills(Profile.join(d.skills()));
        p.setExcludeTitleWords(Profile.join(d.excludeTitleWords()));
        p.setLocations(Profile.join(d.locations()));
        p.setGreenhouseBoards(Profile.join(s.greenhouseBoards()));
        p.setLeverCompanies(Profile.join(s.leverCompanies()));
        p.setRemotiveSearches(Profile.join(s.remotiveSearches()));
        p.setOllamaUrl(props.ollama().url());
        p.setOllamaModel(props.ollama().model());
        return p;
    }

    private static String normalizeList(String v) {
        return Profile.join(Profile.lines(v).stream().map(x -> x.toLowerCase(Locale.ROOT)).distinct().toList());
    }

    /** Accepts a slug or a full board URL like https://boards.greenhouse.io/stripe. */
    private static String normalizeSlugs(String v) {
        return Profile.join(Profile.lines(v).stream()
                .map(x -> x.replaceAll("/+$", ""))
                .map(x -> x.contains("/") ? x.substring(x.lastIndexOf('/') + 1) : x)
                .map(x -> x.toLowerCase(Locale.ROOT))
                .filter(x -> x.matches("[a-z0-9_.-]+"))
                .distinct().toList());
    }

    private static String clean(String v) {
        return v == null ? null : v.strip();
    }

    private static String blankTo(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v;
    }
}
