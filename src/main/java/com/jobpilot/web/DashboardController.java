package com.jobpilot.web;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.JobStatus;
import com.jobpilot.search.JobSearchService;

@Controller
public class DashboardController {

    private final JobRepository jobs;
    private final JobSearchService search;

    public DashboardController(JobRepository jobs, JobSearchService search) {
        this.jobs = jobs;
        this.search = search;
    }

    @GetMapping("/")
    public String board(@RequestParam(defaultValue = "0") int minScore,
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "") String source,
            @RequestParam(defaultValue = "") String location,
            Model model) {
        String qq = q.trim().toLowerCase(Locale.ROOT);
        String loc = location.trim().toLowerCase(Locale.ROOT);
        Predicate<Job> filter = j -> j.getScore() >= minScore
                && (qq.isEmpty() || contains(j.getTitle(), qq) || contains(j.getCompany(), qq))
                && (source.isEmpty() || source.equals(j.getSource()))
                && (loc.isEmpty() || contains(j.getLocation(), loc));

        Map<JobStatus, List<Job>> columns = new LinkedHashMap<>();
        for (JobStatus s : JobStatus.values()) {
            if (s != JobStatus.SKIPPED) {
                columns.put(s, jobs.findByStatusOrderByScoreDescFoundAtDesc(s).stream().filter(filter).toList());
            }
        }
        long applied = countAny(JobStatus.APPLIED, JobStatus.EMAILED, JobStatus.INTERVIEW, JobStatus.OFFER, JobStatus.REJECTED);
        long interviews = countAny(JobStatus.INTERVIEW, JobStatus.OFFER);

        model.addAttribute("tab", "board");
        model.addAttribute("columns", columns);
        model.addAttribute("minScore", minScore);
        model.addAttribute("q", q);
        model.addAttribute("source", source);
        model.addAttribute("location", location);
        model.addAttribute("sources", jobs.sources());
        model.addAttribute("filtered", minScore > 0 || !qq.isEmpty() || !source.isEmpty() || !loc.isEmpty());
        model.addAttribute("statuses", JobStatus.values());
        model.addAttribute("total", jobs.count());
        model.addAttribute("applied", applied);
        model.addAttribute("interviews", interviews);
        model.addAttribute("skipped", jobs.countByStatus(JobStatus.SKIPPED));
        model.addAttribute("interviewRate", applied == 0 ? 0 : Math.round(interviews * 100.0 / applied));
        model.addAttribute("bySource", jobs.countBySource());
        model.addAttribute("running", search.isRunning());
        model.addAttribute("lastRun", search.getLastRun());
        return "board";
    }

    @PostMapping("/jobs/{id}/status")
    @Transactional
    public String setStatus(@PathVariable Long id, @RequestParam JobStatus status,
            @RequestParam(defaultValue = "/") String back) {
        Job job = find(id);
        if (job.getStatus() != status) {
            job.setStatus(status);
            job.setStatusChangedAt(Instant.now());
        }
        return "redirect:" + Web.safeBack(back);
    }

    @PostMapping("/jobs/{id}/notes")
    @Transactional
    public String setNotes(@PathVariable Long id, @RequestParam(defaultValue = "") String notes) {
        find(id).setNotes(notes.length() > 4000 ? notes.substring(0, 4000) : notes);
        return "redirect:/jobs/" + id;
    }

    @PostMapping("/search")
    public String runSearch(RedirectAttributes flash) {
        flash.addFlashAttribute("message", search.startInBackground()
                ? "Search started. The page refreshes by itself until it is done."
                : "A search is already running.");
        return "redirect:/";
    }

    private Job find(Long id) {
        return jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private long countAny(JobStatus... statuses) {
        return Arrays.stream(statuses).mapToLong(jobs::countByStatus).sum();
    }

    private static boolean contains(String field, String needle) {
        return field != null && field.toLowerCase(Locale.ROOT).contains(needle);
    }
}
