package com.jobpilot.web;

import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.jobpilot.apply.ApplyService;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;
import com.jobpilot.search.JobSearchService;
import com.jobpilot.tailor.OllamaClient;

@Controller
public class ProfileController {

    private final ProfileService profiles;
    private final JobSearchService search;
    private final OllamaClient ollama;

    public ProfileController(ProfileService profiles, JobSearchService search, OllamaClient ollama) {
        this.profiles = profiles;
        this.search = search;
        this.ollama = ollama;
    }

    @GetMapping("/profile")
    public String profile(Model model) {
        Profile p = profiles.get();
        model.addAttribute("tab", "profile");
        model.addAttribute("p", p);
        model.addAttribute("hasOriginalPdf", profiles.originalResumePdf() != null);
        model.addAttribute("chrome", ApplyService.chromeInstalled());
        try {
            var models = ollama.models(p);
            model.addAttribute("ollamaOk", true);
            model.addAttribute("ollamaModels", models);
            model.addAttribute("modelInstalled", models.stream().anyMatch(m -> m.equals(p.getOllamaModel())
                    || m.equals(p.getOllamaModel() + ":latest")));
        } catch (Exception e) {
            model.addAttribute("ollamaOk", false);
        }
        return "profile";
    }

    @PostMapping("/profile")
    public String save(@ModelAttribute Profile form, @RequestParam(defaultValue = "false") boolean rescore,
            RedirectAttributes flash) {
        profiles.save(form);
        String msg = "Profile saved.";
        if (rescore) {
            int[] r = search.rescoreAll();
            msg += " Re-scored " + r[0] + " jobs with your new niche" + (r[1] > 0 ? " and moved " + r[1] + " that no longer match to Skipped." : ".");
        }
        flash.addFlashAttribute("message", msg);
        return "redirect:/profile";
    }

    @PostMapping("/profile/resume")
    public String upload(@RequestParam("file") MultipartFile file, RedirectAttributes flash) {
        try {
            if (file.isEmpty()) {
                throw new IllegalArgumentException("Choose a file first.");
            }
            Profile p = profiles.uploadResume(file);
            flash.addFlashAttribute("message", "Resume uploaded: read " + p.getResumeText().length()
                    + " characters. Check the text below and fix anything that came out wrong.");
        } catch (Exception e) {
            flash.addFlashAttribute("error", "Could not read that resume: " + e.getMessage());
        }
        return "redirect:/profile#resume";
    }
}
