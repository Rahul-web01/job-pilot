package com.jobpilot.web;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.outreach.ContactRepository;
import com.jobpilot.outreach.Domains;
import com.jobpilot.outreach.EmailFinderService;
import com.jobpilot.outreach.MailService;
import com.jobpilot.outreach.OutreachEmail;
import com.jobpilot.outreach.OutreachEmailRepository;
import com.jobpilot.outreach.OutreachService;
import com.jobpilot.outreach.OutreachStatus;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;

@Controller
public class OutreachController {

    private final JobRepository jobs;
    private final ContactRepository contacts;
    private final OutreachEmailRepository emails;
    private final EmailFinderService finder;
    private final OutreachService outreach;
    private final ProfileService profiles;
    private final MailService mail;

    public OutreachController(JobRepository jobs, ContactRepository contacts, OutreachEmailRepository emails,
            EmailFinderService finder, OutreachService outreach, ProfileService profiles, MailService mail) {
        this.jobs = jobs;
        this.contacts = contacts;
        this.emails = emails;
        this.finder = finder;
        this.outreach = outreach;
        this.profiles = profiles;
        this.mail = mail;
    }

    /** The Outreach tab: every draft and sent email, with follow-ups that are due. */
    @GetMapping("/outreach")
    public String list(Model model) {
        Profile p = profiles.get();
        List<OutreachEmail> all = emails.findAllByOrderByIdDesc();
        Map<Long, Job> jobsById = jobs.findAllById(all.stream().map(OutreachEmail::getJobId).distinct().toList())
                .stream().collect(Collectors.toMap(Job::getId, Function.identity()));
        model.addAttribute("tab", "outreach");
        model.addAttribute("jobsById", jobsById);
        model.addAttribute("drafts", all.stream().filter(e -> e.getStatus() == OutreachStatus.DRAFT
                || e.getStatus() == OutreachStatus.DRAFTING || e.getStatus() == OutreachStatus.FAILED).toList());
        model.addAttribute("followUps", all.stream().filter(outreach::followUpDue).toList());
        model.addAttribute("sent", all.stream().filter(e -> e.getStatus() == OutreachStatus.SENT
                || e.getStatus() == OutreachStatus.REPLIED).toList());
        model.addAttribute("sentToday", outreach.sentToday());
        model.addAttribute("limit", p.getDailySendLimitOrDefault());
        model.addAttribute("canSend", p.canSendEmail());
        model.addAttribute("busy", all.stream().anyMatch(e -> e.getStatus() == OutreachStatus.DRAFTING));
        return "outreach";
    }

    @PostMapping("/jobs/{id}/domain")
    public String setDomain(@PathVariable Long id, @RequestParam String domain, RedirectAttributes flash) {
        Job job = findJob(id);
        String d = Domains.normalize(domain);
        if (d == null) {
            flash.addFlashAttribute("error", "That doesn't look like a website. Try something like stripe.com.");
        } else {
            job.setCompanyDomain(d);
            jobs.save(job);
            if (!Domains.hasMx(d)) {
                flash.addFlashAttribute("error", d + " can't receive email (no mail server found). Check the spelling.");
            }
        }
        return "redirect:/jobs/" + id + "#outreach";
    }

    @PostMapping("/jobs/{id}/find-contacts")
    public String findContacts(@PathVariable Long id, RedirectAttributes flash) {
        findJob(id);
        flash.addFlashAttribute("message", finder.find(id));
        return "redirect:/jobs/" + id + "#outreach";
    }

    /** Add a known email, or a person's name to generate likely addresses. */
    @PostMapping("/jobs/{id}/contacts")
    public String addContact(@PathVariable Long id, @RequestParam(defaultValue = "") String email,
            @RequestParam(defaultValue = "") String name, @RequestParam(defaultValue = "") String role,
            RedirectAttributes flash) {
        Job job = findJob(id);
        try {
            if (!email.isBlank()) {
                finder.addManual(id, email, name, role);
                flash.addFlashAttribute("message", "Added " + email.strip() + ".");
            } else if (!name.isBlank()) {
                if (job.getCompanyDomain() == null) {
                    throw new IllegalArgumentException("Set the company website first, so I know the email domain.");
                }
                int n = finder.addPersonGuesses(id, name, role);
                flash.addFlashAttribute("message", "Added " + n + " likely addresses for " + name.strip()
                        + ". They're guesses: the first one (first.last@) is the most common pattern.");
            } else {
                throw new IllegalArgumentException("Type an email address, or a person's name.");
            }
        } catch (IllegalArgumentException e) {
            flash.addFlashAttribute("error", e.getMessage());
        }
        return "redirect:/jobs/" + id + "#outreach";
    }

    @PostMapping("/contacts/{id}/delete")
    public String deleteContact(@PathVariable Long id) {
        var c = contacts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        contacts.delete(c);
        return "redirect:/jobs/" + c.getJobId() + "#outreach";
    }

    @PostMapping("/contacts/{id}/draft")
    public String draft(@PathVariable Long id, RedirectAttributes flash) {
        var c = contacts.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
        if (emails.existsByJobIdAndContactIdAndFollowUpOfIdIsNull(c.getJobId(), c.getId())) {
            flash.addFlashAttribute("error", "There's already an email to " + c.getEmail() + " for this job.");
        } else {
            outreach.startDraft(id);
        }
        return "redirect:/jobs/" + c.getJobId() + "#outreach";
    }

    @PostMapping("/outreach/{id}/save")
    public String save(@PathVariable Long id, @RequestParam String subject, @RequestParam String body,
            @RequestParam(defaultValue = "false") boolean attachResume, @RequestParam(defaultValue = "") String back,
            RedirectAttributes flash) {
        OutreachEmail e = findEmail(id);
        outreach.saveDraft(id, subject, body, attachResume);
        flash.addFlashAttribute("message", "Draft saved.");
        return "redirect:" + back(back, e);
    }

    /** Saves your edits, then sends. */
    @PostMapping("/outreach/{id}/send")
    public String send(@PathVariable Long id, @RequestParam String subject, @RequestParam String body,
            @RequestParam(defaultValue = "false") boolean attachResume, @RequestParam(defaultValue = "") String back,
            RedirectAttributes flash) {
        OutreachEmail e = findEmail(id);
        outreach.saveDraft(id, subject, body, attachResume);
        try {
            outreach.send(id);
            flash.addFlashAttribute("message", "Sent to " + e.getToEmail() + ".");
        } catch (IllegalStateException ex) {
            flash.addFlashAttribute("error", ex.getMessage());
        }
        return "redirect:" + back(back, e);
    }

    @PostMapping("/outreach/{id}/replied")
    public String replied(@PathVariable Long id, @RequestParam(defaultValue = "") String back) {
        OutreachEmail e = findEmail(id);
        outreach.markReplied(id);
        return "redirect:" + back(back, e);
    }

    @PostMapping("/outreach/{id}/followup")
    public String followUp(@PathVariable Long id, @RequestParam(defaultValue = "") String back,
            RedirectAttributes flash) {
        OutreachEmail e = findEmail(id);
        outreach.draftFollowUp(id);
        flash.addFlashAttribute("message", "Follow-up drafted. Check it and press Send.");
        return "redirect:" + back(back, e);
    }

    @PostMapping("/outreach/{id}/delete")
    public String delete(@PathVariable Long id, @RequestParam(defaultValue = "") String back) {
        OutreachEmail e = findEmail(id);
        outreach.delete(id);
        return "redirect:" + back(back, e);
    }

    /** Logs in to Gmail without sending anything. */
    @PostMapping("/profile/email-test")
    public String testEmail(RedirectAttributes flash) {
        try {
            mail.testLogin(profiles.get());
            flash.addFlashAttribute("message", "Gmail login works. You can send outreach emails.");
        } catch (Exception e) {
            String m = String.valueOf(e.getMessage());
            flash.addFlashAttribute("error", m.contains("535") || m.toLowerCase().contains("authentication")
                    ? "Gmail rejected the login. Make sure you use an App Password, not your normal password."
                    : "Couldn't log in to Gmail: " + m);
        }
        return "redirect:/profile#email";
    }

    @PostMapping("/profile/email-forget")
    public String forget(RedirectAttributes flash) {
        profiles.forgetEmailPassword();
        flash.addFlashAttribute("message", "App Password removed from this computer.");
        return "redirect:/profile#email";
    }

    private Job findJob(Long id) {
        return jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private OutreachEmail findEmail(Long id) {
        return emails.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static String back(String back, OutreachEmail e) {
        return back.isEmpty() ? "/jobs/" + e.getJobId() + "#outreach" : Web.safeBack(back);
    }
}
