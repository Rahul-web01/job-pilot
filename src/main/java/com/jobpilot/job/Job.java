package com.jobpilot.job;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

@Entity
@Table(name = "job", indexes = {
        @Index(name = "ux_job_external", columnList = "externalKey", unique = true),
        @Index(name = "ix_job_status", columnList = "status")
})
public class Job {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** source + ":" + id at the source; used to avoid duplicates. */
    @Column(nullable = false, length = 300)
    private String externalKey;

    /** Lowercased company|title|location, to catch the same job on two sites. */
    @Column(length = 600)
    private String fingerprint;

    private String source;

    /** Company slug on the platform (Greenhouse board / Lever company), used to open the apply form. */
    private String board;
    private String company;
    private String title;
    private String location;

    @Column(length = 1000)
    private String url;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String description;

    private Instant postedAt;
    private Instant foundAt;
    private Instant statusChangedAt;

    private int score;

    @Column(length = 1000)
    private String matchedSkills;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private JobStatus status = JobStatus.FOUND;

    @Column(length = 4000)
    private String notes;

    // ---- Tailoring (resume + cover letter) ----
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskState tailorState;

    @Column(length = 1000)
    private String tailorMessage;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String tailoredSummary;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String coverLetter;

    private Instant tailoredAt;

    // ---- Auto-fill ----
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskState applyState;

    @Column(length = 4000)
    private String applyMessage;

    public Long getId() { return id; }
    public String getExternalKey() { return externalKey; }
    public void setExternalKey(String externalKey) { this.externalKey = externalKey; }
    public String getFingerprint() { return fingerprint; }
    public void setFingerprint(String fingerprint) { this.fingerprint = fingerprint; }
    public String getBoard() { return board; }
    public void setBoard(String board) { this.board = board; }
    public TaskState getTailorState() { return tailorState; }
    public void setTailorState(TaskState v) { this.tailorState = v; }
    public String getTailorMessage() { return tailorMessage; }
    public void setTailorMessage(String v) { this.tailorMessage = v; }
    public String getTailoredSummary() { return tailoredSummary; }
    public void setTailoredSummary(String v) { this.tailoredSummary = v; }
    public String getCoverLetter() { return coverLetter; }
    public void setCoverLetter(String v) { this.coverLetter = v; }
    public Instant getTailoredAt() { return tailoredAt; }
    public void setTailoredAt(Instant v) { this.tailoredAt = v; }
    public TaskState getApplyState() { return applyState; }
    public void setApplyState(TaskState v) { this.applyState = v; }
    public String getApplyMessage() { return applyMessage; }
    public void setApplyMessage(String v) { this.applyMessage = v; }
    public boolean isTailored() { return tailoredAt != null; }
    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }
    public String getCompany() { return company; }
    public void setCompany(String company) { this.company = company; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public String getLocation() { return location; }
    public void setLocation(String location) { this.location = location; }
    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public Instant getPostedAt() { return postedAt; }
    public void setPostedAt(Instant postedAt) { this.postedAt = postedAt; }
    public Instant getFoundAt() { return foundAt; }
    public void setFoundAt(Instant foundAt) { this.foundAt = foundAt; }
    public Instant getStatusChangedAt() { return statusChangedAt; }
    public void setStatusChangedAt(Instant statusChangedAt) { this.statusChangedAt = statusChangedAt; }
    public int getScore() { return score; }
    public void setScore(int score) { this.score = score; }
    public String getMatchedSkills() { return matchedSkills; }
    public void setMatchedSkills(String matchedSkills) { this.matchedSkills = matchedSkills; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }
}
