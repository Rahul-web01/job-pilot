package com.jobpilot.web;

import java.util.List;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.jobpilot.autopilot.AutopilotService;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.JobStatus;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;
import com.jobpilot.search.JobSearchService;

@Controller
public class AutopilotController {

    private final AutopilotService autopilot;
    private final ProfileService profiles;
    private final JobSearchService search;
    private final JobRepository jobs;

    public AutopilotController(AutopilotService autopilot, ProfileService profiles, JobSearchService search, JobRepository jobs) {
        this.autopilot = autopilot;
        this.profiles = profiles;
        this.search = search;
        this.jobs = jobs;
    }

    @GetMapping("/autopilot")
    public String page(Model model) {
        Profile p = profiles.get();
        model.addAttribute("tab", "autopilot");
        model.addAttribute("p", p);
        model.addAttribute("running", autopilot.isRunning());
        model.addAttribute("step", autopilot.getStep());
        model.addAttribute("history", autopilot.getHistory());
        model.addAttribute("waiting", autopilot.waitingForYou());
        model.addAttribute("shortlisted", jobs.countByStatus(JobStatus.SHORTLISTED));
        model.addAttribute("tailored", jobs.countByStatus(JobStatus.TAILORED));
        model.addAttribute("applied", jobs.countByStatus(JobStatus.APPLIED));
        model.addAttribute("aboveMin", jobs.findByStatusOrderByScoreDescFoundAtDesc(JobStatus.FOUND).stream()
                .filter(j -> j.getScore() >= p.getAutoMinScore()).count());
        return "autopilot";
    }

    @PostMapping("/autopilot/settings")
    public String save(@RequestParam(defaultValue = "") String experienceLevel, @RequestParam(defaultValue = "") String years,
            @RequestParam(defaultValue = "false") boolean autoShortlist, @RequestParam(required = false) Integer autoMinScore,
            @RequestParam(defaultValue = "false") boolean autoTailor, @RequestParam(defaultValue = "false") boolean autoFill,
            @RequestParam(required = false) Integer autoMaxPerRun, @RequestParam(required = false) Integer autoMaxWaiting,
            RedirectAttributes flash) {
        profiles.saveAutopilot(experienceLevel, years, autoShortlist, autoMinScore, autoTailor, autoFill,
                autoMaxPerRun, autoMaxWaiting);
        int[] r = search.rescoreAll();
        String msg = "Autopilot settings saved. Re-scored " + r[0] + " jobs for your experience level" + (r[1] > 0 ? "; "
                + r[1] + " new jobs that don't fit moved to Skipped." : ".");
        flash.addFlashAttribute("message", msg);
        return "redirect:/autopilot";
    }

    @PostMapping("/autopilot/run")
    public String run(@RequestParam(defaultValue = "true") boolean withSearch, RedirectAttributes flash) {
        Profile p = profiles.get();
        if (!p.isAutoShortlist() && !p.isAutoTailor() && !p.isAutoFill()) {
            flash.addFlashAttribute("error", "Switch on at least one Autopilot step first.");
        } else if (withSearch ? autopilot.startWithSearch() : autopilot.startWithoutSearch()) {
            flash.addFlashAttribute("message", withSearch ? "Searching now; Autopilot continues when the search ends."
                    : "Autopilot started on the jobs you already have.");
        } else {
            flash.addFlashAttribute("error", "Autopilot or a search is already running.");
        }
        return "redirect:/autopilot";
    }

    static List<String> levels() {
        return List.of("FRESHER", "EXPERIENCED");
    }
}
