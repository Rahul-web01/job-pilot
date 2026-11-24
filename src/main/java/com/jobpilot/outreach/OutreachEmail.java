package com.jobpilot.outreach;

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

/** One email to one contact about one job: a draft until you press Send. */
@Entity
@Table(name = "outreach_email", indexes = {
        @Index(name = "ix_outreach_job", columnList = "jobId"),
        @Index(name = "ix_outreach_sent", columnList = "sentAt")
})
public class OutreachEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;
    private Long contactId;
    private String toEmail;
    private String toName;

    /** Set on a follow-up: the email it follows up on. */
    private Long followUpOfId;

    @Column(length = 500)
    private String subject;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private OutreachStatus status;

    @Column(length = 1000)
    private String error;

    /** Message-ID of the sent email, so a follow-up lands in the same thread. */
    @Column(length = 500)
    private String messageId;

    private boolean attachResume = true;
    private Instant createdAt;
    private Instant sentAt;

    public boolean isFollowUp() { return followUpOfId != null; }

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long v) { this.jobId = v; }
    public Long getContactId() { return contactId; }
    public void setContactId(Long v) { this.contactId = v; }
    public String getToEmail() { return toEmail; }
    public void setToEmail(String v) { this.toEmail = v; }
    public String getToName() { return toName; }
    public void setToName(String v) { this.toName = v; }
    public Long getFollowUpOfId() { return followUpOfId; }
    public void setFollowUpOfId(Long v) { this.followUpOfId = v; }
    public String getSubject() { return subject; }
    public void setSubject(String v) { this.subject = v; }
    public String getBody() { return body; }
    public void setBody(String v) { this.body = v; }
    public OutreachStatus getStatus() { return status; }
    public void setStatus(OutreachStatus v) { this.status = v; }
    public String getError() { return error; }
    public void setError(String v) { this.error = v; }
    public String getMessageId() { return messageId; }
    public void setMessageId(String v) { this.messageId = v; }
    public boolean isAttachResume() { return attachResume; }
    public void setAttachResume(boolean v) { this.attachResume = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
    public Instant getSentAt() { return sentAt; }
    public void setSentAt(Instant v) { this.sentAt = v; }
}
