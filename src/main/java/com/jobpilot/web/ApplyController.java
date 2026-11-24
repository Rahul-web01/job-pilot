package com.jobpilot.web;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.support.RedirectAttributes;

import com.jobpilot.apply.ApplyService;
import com.jobpilot.job.Job;
import com.jobpilot.job.JobRepository;
import com.jobpilot.job.JobStatus;
import com.jobpilot.job.TaskState;
import com.jobpilot.outreach.ContactRepository;
import com.jobpilot.outreach.OutreachService;
import com.jobpilot.outreach.OutreachStatus;
import com.jobpilot.profile.Profile;
import com.jobpilot.profile.ProfileService;
import com.jobpilot.tailor.PdfService;
import com.jobpilot.tailor.TailorService;

@Controller
public class ApplyController {

    private final JobRepository jobs;
    private final ProfileService profiles;
    private final TailorService tailor;
    private final ApplyService apply;
    private final PdfService pdfs;
    private final ContactRepository contacts;
    private final OutreachService outreach;

    public ApplyController(JobRepository jobs, ProfileService profiles, TailorService tailor, ApplyService apply,
            PdfService pdfs, ContactRepository contacts, OutreachService outreach) {
        this.jobs = jobs;
        this.profiles = profiles;
        this.tailor = tailor;
        this.apply = apply;
        this.pdfs = pdfs;
        this.contacts = contacts;
        this.outreach = outreach;
    }

    /** The Apply tab: jobs you shortlisted, ready to tailor and apply to. */
    @GetMapping("/apply")
    public String queue(Model model) {
        Profile p = profiles.get();
        List<Job> queue = jobs.findByStatusInOrderByStatusChangedAtDesc(List.of(JobStatus.SHORTLISTED, JobStatus.TAILORED));
        List<Job> done = jobs.findByStatusInOrderByStatusChangedAtDesc(List.of(JobStatus.APPLIED, JobStatus.EMAILED,
                JobStatus.INTERVIEW, JobStatus.OFFER, JobStatus.REJECTED));
        model.addAttribute("tab", "apply");
        model.addAttribute("queue", queue);
        model.addAttribute("done", done.size() > 30 ? done.subList(0, 30) : done);
        model.addAttribute("problem", tailor.problem(p));
        model.addAttribute("chrome", ApplyService.chromeInstalled());
        model.addAttribute("busy", queue.stream().anyMatch(j -> j.getTailorState() == TaskState.RUNNING
                || j.getApplyState() == TaskState.RUNNING));
        return "apply";
    }

    @GetMapping("/jobs/{id}")
    public String job(@PathVariable Long id, Model model) {
        Job job = find(id);
        Profile p = profiles.get();
        model.addAttribute("tab", "board");
        model.addAttribute("job", job);
        model.addAttribute("statuses", JobStatus.values());
        model.addAttribute("problem", tailor.problem(p));
        model.addAttribute("hasResumePdf", pdfs.file(job, PdfService.RESUME) != null);
        model.addAttribute("hasCoverPdf", pdfs.file(job, PdfService.COVER_LETTER) != null);
        model.addAttribute("applyUrl", ApplyService.applyUrl(job));
        model.addAttribute("autoFillSupported", "greenhouse".equals(job.getSource()) || "lever".equals(job.getSource()));
        var mails = outreach.forJob(id);
        model.addAttribute("contacts", contacts.findByJobIdOrderByConfidenceDescIdAsc(id));
        model.addAttribute("mails", mails);
        model.addAttribute("emailedContactIds", mails.stream().filter(m -> !m.isFollowUp()).map(m -> m.getContactId()).toList());
        model.addAttribute("canSend", p.canSendEmail());
        model.addAttribute("sentToday", outreach.sentToday());
        model.addAttribute("limit", p.getDailySendLimitOrDefault());
        model.addAttribute("hasAnyResume", pdfs.file(job, PdfService.RESUME) != null || profiles.originalResumePdf() != null);
        model.addAttribute("busy", job.getTailorState() == TaskState.RUNNING || job.getApplyState() == TaskState.RUNNING
                || mails.stream().anyMatch(m -> m.getStatus() == OutreachStatus.DRAFTING));
        return "job";
    }

    @PostMapping("/jobs/{id}/tailor")
    public String startTailor(@PathVariable Long id, @RequestParam(defaultValue = "") String back,
            RedirectAttributes flash) {
        find(id);
        String problem = tailor.problem(profiles.get());
        if (problem != null) {
            flash.addFlashAttribute("error", problem);
        } else {
            tailor.startInBackground(id);
        }
        return "redirect:" + (back.isEmpty() ? "/jobs/" + id : Web.safeBack(back));
    }

    @PostMapping("/jobs/{id}/documents")
    public String saveDocuments(@PathVariable Long id, @RequestParam(defaultValue = "") String summary,
            @RequestParam(defaultValue = "") String coverLetter, RedirectAttributes flash) {
        find(id);
        try {
            tailor.saveEdits(id, summary, coverLetter);
        } catch (IOException e) {
            flash.addFlashAttribute("error", "Could not build the PDFs: " + e.getMessage());
        }
        return "redirect:/jobs/" + id + "#documents";
    }

    @PostMapping("/jobs/{id}/autofill")
    public String autofill(@PathVariable Long id, @RequestParam(defaultValue = "") String back,
            RedirectAttributes flash) {
        find(id);
        Profile p = profiles.get();
        if (!p.hasContactDetails()) {
            flash.addFlashAttribute("error", "Fill in at least your first name and email on the Profile tab first.");
        } else {
            apply.startInBackground(id);
        }
        return "redirect:" + (back.isEmpty() ? "/jobs/" + id : Web.safeBack(back));
    }

    @PostMapping("/jobs/{id}/submitted")
    public String submitted(@PathVariable Long id, @RequestParam(defaultValue = "") String back) {
        Job job = find(id);
        job.setStatus(JobStatus.APPLIED);
        job.setStatusChangedAt(Instant.now());
        job.setApplyMessage("Marked as applied on " + java.time.LocalDate.now() + ".");
        jobs.save(job);
        return "redirect:" + (back.isEmpty() ? "/jobs/" + id : Web.safeBack(back));
    }

    /** Shows a generated PDF in the browser. */
    @GetMapping("/jobs/{id}/files/{kind}")
    public ResponseEntity<byte[]> file(@PathVariable Long id, @PathVariable String kind) throws IOException {
        Job job = find(id);
        String name = switch (kind) {
            case "resume" -> PdfService.RESUME;
            case "cover-letter" -> PdfService.COVER_LETTER;
            default -> throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        };
        Path path = pdfs.file(job, name);
        if (path == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        String nice = PdfService.niceName(profiles.get(), job, kind.equals("resume") ? "Resume" : "Cover Letter");
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.inline().filename(nice).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(Files.readAllBytes(path));
    }

    private Job find(Long id) {
        return jobs.findById(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
