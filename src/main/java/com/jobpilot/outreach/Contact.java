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
import jakarta.persistence.Table;

/** A person or inbox at the company you could email about one job. */
@Entity
@Table(name = "contact", indexes = @Index(name = "ix_contact_job", columnList = "jobId"))
public class Contact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long jobId;
    private String email;
    private String name;
    private String role;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private ContactSource source;

    /** 0-100, higher is more likely to be a real, relevant inbox. */
    private int confidence;

    @Column(length = 1000)
    private String foundAt;

    private Instant createdAt;

    public String getFirstName() {
        if (name == null || name.isBlank()) {
            return null;
        }
        return name.strip().split("\\s+")[0];
    }

    public Long getId() { return id; }
    public Long getJobId() { return jobId; }
    public void setJobId(Long v) { this.jobId = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getName() { return name; }
    public void setName(String v) { this.name = v; }
    public String getRole() { return role; }
    public void setRole(String v) { this.role = v; }
    public ContactSource getSource() { return source; }
    public void setSource(ContactSource v) { this.source = v; }
    public int getConfidence() { return confidence; }
    public void setConfidence(int v) { this.confidence = v; }
    public String getFoundAt() { return foundAt; }
    public void setFoundAt(String v) { this.foundAt = v; }
    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant v) { this.createdAt = v; }
}
