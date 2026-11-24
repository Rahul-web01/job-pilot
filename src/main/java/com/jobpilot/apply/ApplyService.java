package com.jobpilot.apply;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.TaskState;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;
import com.jobpilot.tailor.PdfService;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;

import jakarta.annotation.PreDestroy;

/**
 * Opens a job's application form in a real Chrome window and fills it in.
 * The window stays open so you can check everything, answer custom questions and press Submit yourself.
 */
@Service
public class ApplyService {

    private static final Logger log = LoggerFactory.getLogger(ApplyService.class);
    private static final List<String> CHROME_PATHS = List.of(
            "/usr/bin/google-chrome", "/usr/bin/google-chrome-stable",
            "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
            "C:\\Program Files\\Google\\Chrome\\Application\\chrome.exe",
            "C:\\Program Files (x86)\\Google\\Chrome\\Application\\chrome.exe");

    /** Playwright objects must only be used from one thread. */
    private final ExecutorService browserThread = Executors.newSingleThreadExecutor(r -> new Thread(r, "browser"));
    private final JobRepository jobs;
    private final ProfileService profiles;
    private final PdfService pdfs;
    private final boolean headless;
    private Playwright playwright;
    private Browser browser;

    public ApplyService(JobRepository jobs, ProfileService profiles, PdfService pdfs,
            @Value("${jobpilot.browser-headless:false}") boolean headless) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.pdfs = pdfs;
        this.headless = headless;
    }

    public static boolean chromeInstalled() {
        return CHROME_PATHS.stream().anyMatch(p -> Files.exists(Path.of(p)));
    }

    /** The page with the actual form for this job. */
    public static String applyUrl(Job job) {
        String id = job.getExternalKey().substring(job.getExternalKey().indexOf(':') + 1);
        if ("greenhouse".equals(job.getSource()) && job.getBoard() != null) {
            return "https://job-boards.greenhouse.io/embed/job_app?for=" + job.getBoard() + "&token=" + id;
        }
        if ("lever".equals(job.getSource()) && job.getUrl() != null && !job.getUrl().endsWith("/apply")) {
            return job.getUrl() + "/apply";
        }
        return job.getUrl();
    }

    public void startInBackground(long jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        if (job.getApplyState() == TaskState.RUNNING) {
            return;
        }
        job.setApplyState(TaskState.RUNNING);
        job.setApplyMessage("Opening the form in Chrome…");
        jobs.save(job);
        browserThread.submit(() -> run(jobId));
    }

    private void run(long jobId) {
        Job job = jobs.findById(jobId).orElseThrow();
        Profile profile = profiles.get();
        try {
            Page page = browser().newContext(new Browser.NewContextOptions().setViewportSize(null)).newPage();
            page.navigate(applyUrl(job));
            page.waitForLoadState();
            page.waitForTimeout(2000); // let React-based forms render

            Path resume = pdfs.file(job, PdfService.RESUME);
            if (resume == null) {
                resume = profiles.originalResumePdf();
            }
            var result = new FormFiller(page, profile).fill(
                    resume == null ? null : new FormFiller.Upload(resume, PdfService.niceName(profile, job, "Resume")),
                    pdfs.file(job, PdfService.COVER_LETTER) == null ? null
                            : new FormFiller.Upload(pdfs.file(job, PdfService.COVER_LETTER),
                                    PdfService.niceName(profile, job, "Cover Letter")),
                    job.getCoverLetter());
            page.bringToFront();

            StringBuilder msg = new StringBuilder();
            msg.append(result.filled().isEmpty()
                    ? "Opened the form but could not recognise any fields. Fill it in yourself in the Chrome window."
                    : "Filled: " + String.join(", ", result.filled()) + ".");
            if (!result.needsYou().isEmpty()) {
                msg.append(" Still needs you: ").append(String.join(" · ", result.needsYou())).append(".");
            }
            msg.append(" Check everything in the Chrome window, press Submit there, then click \"I submitted it\".");
            job.setApplyState(TaskState.DONE);
            job.setApplyMessage(cut(msg.toString(), 4000));
        } catch (Exception e) {
            log.warn("Auto-fill for job {} failed", jobId, e);
            job.setApplyState(TaskState.ERROR);
            job.setApplyMessage(cut("Could not fill the form: " + e.getMessage(), 4000));
        }
        jobs.save(job);
    }

    private Browser browser() {
        if (browser != null && browser.isConnected()) {
            return browser;
        }
        if (playwright == null) {
            // With Chrome installed, use it and skip Playwright's own browser download (~150 MB)
            playwright = chromeInstalled()
                    ? Playwright.create(new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")))
                    : Playwright.create();
        }
        var options = new BrowserType.LaunchOptions().setHeadless(headless);
        if (chromeInstalled()) {
            options.setChannel("chrome");
        }
        if (!headless) {
            options.setArgs(List.of("--start-maximized"));
        }
        browser = playwright.chromium().launch(options);
        return browser;
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    @PreDestroy
    void shutdown() {
        browserThread.submit(() -> {
            if (playwright != null) {
                playwright.close();
            }
        });
        browserThread.shutdown();
    }
}
