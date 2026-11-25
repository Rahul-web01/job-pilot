package com.jobpilot.search;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.JobStatus;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;
import com.jobpilot.source.JobPosting;
import com.jobpilot.source.JobSource;

@Service
public class JobSearchService {

    private static final Logger log = LoggerFactory.getLogger(JobSearchService.class);

    /** perSource value = {fetched, matched, new} */
    public record RunSummary(Instant finishedAt, Map<String, int[]> perSource, int added) {
    }

    private final List<JobSource> sources;
    private final JobMatcher matcher;
    private final JobRepository jobs;
    private final ProfileService profiles;
    private final ApplicationEventPublisher events;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile RunSummary lastRun;

    public JobSearchService(List<JobSource> sources, JobMatcher matcher, JobRepository jobs, ProfileService profiles,
            ApplicationEventPublisher events) {
        this.events = events;
        this.sources = sources;
        this.matcher = matcher;
        this.jobs = jobs;
        this.profiles = profiles;
    }

    public boolean isRunning() {
        return running.get();
    }

    public RunSummary getLastRun() {
        return lastRun;
    }

    /** Starts a search in the background; returns false if one is already running. */
    public boolean startInBackground() {
        if (running.get()) {
            return false;
        }
        Thread.ofVirtual().name("job-search").start(this::runSearch);
        return true;
    }

    @Scheduled(cron = "${jobpilot.search-cron}")
    public void scheduledSearch() {
        runSearch();
    }

    public void runSearch() {
        if (!running.compareAndSet(false, true)) {
            log.info("Search already running, skipping");
            return;
        }
        SearchFinishedEvent finished = null;
        try {
            Profile profile = profiles.get();
            Map<String, int[]> perSource = new LinkedHashMap<>();
            int added = 0;
            for (JobSource source : sources) {
                int[] counts = new int[3];
                List<JobPosting> postings = source.fetch(profile);
                counts[0] = postings.size();
                for (JobPosting p : postings) {
                    var match = matcher.evaluate(p, profile);
                    if (!match.keep()) {
                        continue;
                    }
                    counts[1]++;
                    if (save(p, match)) {
                        counts[2]++;
                        added++;
                    }
                }
                perSource.put(source.name(), counts);
                log.info("{}: fetched {}, matched {}, new {}", source.name(), counts[0], counts[1], counts[2]);
            }
            lastRun = new RunSummary(Instant.now(), perSource, added);
            finished = new SearchFinishedEvent(added);
        } catch (Exception e) {
            log.error("Job search failed", e);
        } finally {
            running.set(false);
        }
        if (finished != null) {
            events.publishEvent(finished); // Autopilot carries on from here
        }
    }

    /**
     * Re-scores every saved job with the current profile (after the niche changes).
     * New jobs that no longer match are moved to Skipped; jobs you already acted on are left alone.
     */
    @Transactional
    public int[] rescoreAll() {
        Profile profile = profiles.get();
        int rescored = 0;
        int skipped = 0;
        for (Job j : jobs.findAll()) {
            JobPosting p = new JobPosting(j.getSource(), null, j.getBoard(), j.getCompany(), j.getTitle(),
                    j.getLocation(), j.getUrl(), j.getDescription(), j.getPostedAt());
            var match = matcher.evaluate(p, profile);
            j.setScore(match.score());
            j.setMatchedSkills(truncate(String.join(", ", match.matchedSkills()), 1000));
            rescored++;
            if (!match.keep() && j.getStatus() == JobStatus.FOUND) {
                j.setStatus(JobStatus.SKIPPED);
                j.setStatusChangedAt(Instant.now());
                skipped++;
            }
        }
        return new int[] { rescored, skipped };
    }

    private boolean save(JobPosting p, JobMatcher.Match match) {
        if (p.sourceId() == null) {
            return false;
        }
        var existing = jobs.findByExternalKey(p.externalKey());
        if (existing.isPresent()) {
            Job j = existing.get();
            if (j.getBoard() == null && p.board() != null) {
                j.setBoard(p.board());
                jobs.save(j);
            }
            return false;
        }
        String fingerprint = fingerprint(p);
        if (jobs.existsByFingerprint(fingerprint)) {
            return false;
        }
        Job job = new Job();
        job.setExternalKey(p.externalKey());
        job.setFingerprint(fingerprint);
        job.setSource(p.source());
        job.setBoard(p.board());
        job.setCompany(truncate(p.company(), 255));
        job.setTitle(truncate(p.title(), 255));
        job.setLocation(truncate(p.location(), 255));
        job.setUrl(truncate(p.url(), 1000));
        job.setDescription(p.description());
        job.setPostedAt(p.postedAt());
        job.setFoundAt(Instant.now());
        job.setStatusChangedAt(Instant.now());
        job.setScore(match.score());
        job.setMatchedSkills(truncate(String.join(", ", match.matchedSkills()), 1000));
        job.setStatus(JobStatus.FOUND);
        jobs.save(job);
        return true;
    }

    private static String fingerprint(JobPosting p) {
        return truncate((norm(p.company()) + "|" + norm(p.title()) + "|" + norm(p.location())), 600);
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", " ").trim();
    }

    private static String truncate(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }
}
