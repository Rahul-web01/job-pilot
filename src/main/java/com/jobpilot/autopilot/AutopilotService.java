package com.jobpilot.autopilot;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import com.jobpilot.apply.ApplyService;
import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.JobStatus;
import com.jobpilot.job.TaskState;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;
import com.jobpilot.search.JobMatcher;
import com.jobpilot.search.JobSearchService;
import com.jobpilot.search.SearchFinishedEvent;
import com.jobpilot.tailor.TailorService;

/**
 * After every job search: shortlists jobs scoring at least your minimum, tailors their resume and cover letter,
 * and auto-fills their application forms in Chrome, one job at a time. Each switch is on the Autopilot tab.
 * It never presses Submit: filled forms wait for you, and it stops filling when too many are waiting.
 */
@Service
public class AutopilotService {

    private static final Logger log = LoggerFactory.getLogger(AutopilotService.class);

    public record Entry(Instant at, String text) {
    }

    private final JobRepository jobs;
    private final ProfileService profiles;
    private final TailorService tailor;
    private final ApplyService apply;
    private final JobSearchService search;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Deque<Entry> history = new ArrayDeque<>();
    private volatile String step = "";

    public AutopilotService(JobRepository jobs, ProfileService profiles, TailorService tailor, ApplyService apply,
            JobSearchService search) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.tailor = tailor;
        this.apply = apply;
        this.search = search;
    }

    public boolean isRunning() {
        return running.get() || search.isRunning();
    }

    public String getStep() {
        return search.isRunning() ? "Searching for jobs…" : step;
    }

    public synchronized List<Entry> getHistory() {
        return new ArrayList<>(history);
    }

    /** Search now; when it finishes, the rest follows automatically. */
    public boolean startWithSearch() {
        return search.startInBackground();
    }

    /** Skip the search and work on what's already found/shortlisted. */
    public boolean startWithoutSearch() {
        if (running.get()) {
            return false;
        }
        Thread.ofVirtual().name("autopilot").start(this::run);
        return true;
    }

    @EventListener
    public void onSearchFinished(SearchFinishedEvent e) {
        Profile p = profiles.get();
        if (p.isAutoShortlist() || p.isAutoTailor() || p.isAutoFill()) {
            note("Search finished: " + e.added() + " new job" + (e.added() == 1 ? "" : "s") + ".");
            startWithoutSearch();
        }
    }

    void run() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        try {
            Profile p = profiles.get();
            int budget = p.getAutoMaxPerRun();

            // 1. Shortlist
            if (p.isAutoShortlist()) {
                step = "Shortlisting jobs scoring " + p.getAutoMinScore() + "+…";
                List<Job> best = jobs.findByStatusOrderByScoreDescFoundAtDesc(JobStatus.FOUND).stream()
                        .filter(j -> fits(j, p))
                        .limit(budget)
                        .toList();
                for (Job j : best) {
                    j.setStatus(JobStatus.SHORTLISTED);
                    j.setStatusChangedAt(Instant.now());
                    jobs.save(j);
                }
                note(best.isEmpty() ? "No new jobs scored " + p.getAutoMinScore() + " or more."
                        : "Shortlisted " + best.size() + ": " + names(best) + ".");
            }

            // 2 + 3. Tailor, then fill, one job at a time
            if (p.isAutoTailor()) {
                String problem = tailor.problem(p);
                if (problem != null) {
                    note("Can't tailor yet: " + problem);
                } else {
                    List<Job> toTailor = jobs.findByStatusOrderByScoreDescFoundAtDesc(JobStatus.SHORTLISTED).stream()
                            .filter(j -> !j.isTailored() && j.getTailorState() != TaskState.RUNNING && fits(j, p))
                            .limit(budget)
                            .toList();
                    for (Job j : toTailor) {
                        step = "Tailoring resume and cover letter: " + label(j) + "…";
                        boolean ok = tailor.tailorNow(j.getId());
                        note(ok ? "Tailored " + label(j) + "." : "Tailoring failed for " + label(j) + ": "
                                + jobs.findById(j.getId()).map(Job::getTailorMessage).orElse("unknown error"));
                        if (ok && p.isAutoFill()) {
                            fillIfRoom(jobs.findById(j.getId()).orElseThrow(), p);
                        }
                    }
                }
            }
            // forms not filled yet (e.g. tailored earlier, or auto-fill was switched on later)
            if (p.isAutoFill()) {
                List<Job> ready = jobs.findByStatusOrderByScoreDescFoundAtDesc(JobStatus.TAILORED).stream()
                        .filter(j -> j.getApplyState() == null && fits(j, p))
                        .limit(budget)
                        .toList();
                for (Job j : ready) {
                    if (!fillIfRoom(j, p)) {
                        break;
                    }
                }
            }
            note("Done. " + waitingForYou().size() + " filled form" + (waitingForYou().size() == 1 ? "" : "s")
                    + " waiting for your Submit.");
        } catch (Exception e) {
            log.warn("Autopilot run failed", e);
            note("Autopilot stopped with an error: " + e.getMessage());
        } finally {
            step = "";
            running.set(false);
        }
    }

    /** Auto-fills one job unless too many filled forms are already waiting. Returns false when it's full. */
    private boolean fillIfRoom(Job j, Profile p) {
        if (!"greenhouse".equals(j.getSource()) && !"lever".equals(j.getSource())) {
            note(label(j) + " is on a site Auto-fill doesn't support; apply from the job page.");
            return true;
        }
        if (waitingForYou().size() >= p.getAutoMaxWaiting()) {
            note("Paused filling: " + p.getAutoMaxWaiting() + " filled forms are already waiting for you. Submit them"
                    + " (and click Submitted) and Autopilot continues on the next run.");
            return false;
        }
        step = "Filling the application in Chrome: " + label(j) + "…";
        boolean ok = apply.fillNow(j.getId());
        note(ok ? "Filled the form for " + label(j) + ". Check it in Chrome and press Submit."
                : "Couldn't fill the form for " + label(j) + ".");
        return true;
    }

    /**
     * Autopilot only touches jobs that still fit you: score at or above your minimum and a location you want.
     * Jobs you shortlisted yourself that don't fit stay on the Apply tab for you.
     */
    static boolean fits(Job j, Profile p) {
        List<String> places = Profile.lines(p.getLocations());
        return j.getScore() >= p.getAutoMinScore() && (places.isEmpty() || JobMatcher.locationMatches(j.getLocation(), places));
    }

    /** Filled forms you haven't submitted yet. */
    public List<Job> waitingForYou() {
        return jobs.findByStatusInOrderByStatusChangedAtDesc(List.of(JobStatus.SHORTLISTED, JobStatus.TAILORED)).stream()
                .filter(j -> j.getApplyState() == TaskState.DONE)
                .sorted(Comparator.comparing(Job::getId))
                .toList();
    }

    private synchronized void note(String text) {
        log.info("Autopilot: {}", text);
        history.addFirst(new Entry(Instant.now(), text));
        while (history.size() > 50) {
            history.removeLast();
        }
    }

    private static String label(Job j) {
        return j.getTitle() + " at " + j.getCompany();
    }

    private static String names(List<Job> js) {
        return String.join(", ", js.stream().map(AutopilotService::label).toList());
    }
}
