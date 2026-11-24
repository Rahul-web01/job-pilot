package com.jobpilot.profile;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
import jakarta.persistence.Table;

/**
 * The one user of this copy of JobPilot: their details, niche and resume.
 * Everything here is edited on the Profile tab and stays on this computer (./data).
 */
@Entity
@Table(name = "profile")
public class Profile {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    // ---- Personal details used to fill application forms ----
    private String firstName;
    private String lastName;
    private String email;
    private String phone;
    private String city;
    private String linkedinUrl;
    private String githubUrl;
    private String portfolioUrl;
    private String currentCompany;
    private String yearsExperience;
    private String noticePeriod;
    private String expectedSalary;
    private String workAuthorization;

    // ---- Niche and filters (one item per line) ----
    @Column(length = 4000) private String targetTitles;
    @Column(length = 4000) private String skills;
    @Column(length = 4000) private String excludeTitleWords;
    @Column(length = 4000) private String locations;

    // ---- Where to search ----
    @Column(length = 4000) private String greenhouseBoards;
    @Column(length = 4000) private String leverCompanies;
    @Column(length = 4000) private String remotiveSearches;

    // ---- Resume ----
    private String resumeFileName;
    private Instant resumeUploadedAt;

    @Lob
    @Column(columnDefinition = "CLOB")
    private String resumeText;

    // ---- Sending outreach emails (your own Gmail) ----
    private String smtpEmail;
    /** Gmail App Password. Stored only in ./data on this computer. */
    private String smtpPassword;
    private Integer dailySendLimit;

    // ---- Free local AI (Ollama) ----
    private String ollamaUrl;
    private String ollamaModel;

    /** Splits a one-per-line (or comma separated) field into trimmed, non-empty items. */
    public static List<String> lines(String value) {
        if (value == null) {
            return List.of();
        }
        return Arrays.stream(value.split("[\\n,]"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    public static String join(List<String> items) {
        return items == null ? "" : String.join("\n", items);
    }

    public String getFullName() {
        return ((firstName == null ? "" : firstName) + " " + (lastName == null ? "" : lastName)).trim();
    }

    public boolean canSendEmail() {
        return notBlank(smtpEmail) && notBlank(smtpPassword);
    }

    public int getDailySendLimitOrDefault() {
        return dailySendLimit == null || dailySendLimit < 1 ? 20 : Math.min(dailySendLimit, 100);
    }

    public boolean hasResume() {
        return resumeText != null && !resumeText.isBlank();
    }

    public boolean hasContactDetails() {
        return notBlank(firstName) && notBlank(email);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    public Long getId() { return id; }
    public String getFirstName() { return firstName; }
    public void setFirstName(String v) { this.firstName = v; }
    public String getLastName() { return lastName; }
    public void setLastName(String v) { this.lastName = v; }
    public String getEmail() { return email; }
    public void setEmail(String v) { this.email = v; }
    public String getPhone() { return phone; }
    public void setPhone(String v) { this.phone = v; }
    public String getCity() { return city; }
    public void setCity(String v) { this.city = v; }
    public String getLinkedinUrl() { return linkedinUrl; }
    public void setLinkedinUrl(String v) { this.linkedinUrl = v; }
    public String getGithubUrl() { return githubUrl; }
    public void setGithubUrl(String v) { this.githubUrl = v; }
    public String getPortfolioUrl() { return portfolioUrl; }
    public void setPortfolioUrl(String v) { this.portfolioUrl = v; }
    public String getCurrentCompany() { return currentCompany; }
    public void setCurrentCompany(String v) { this.currentCompany = v; }
    public String getYearsExperience() { return yearsExperience; }
    public void setYearsExperience(String v) { this.yearsExperience = v; }
    public String getNoticePeriod() { return noticePeriod; }
    public void setNoticePeriod(String v) { this.noticePeriod = v; }
    public String getExpectedSalary() { return expectedSalary; }
    public void setExpectedSalary(String v) { this.expectedSalary = v; }
    public String getWorkAuthorization() { return workAuthorization; }
    public void setWorkAuthorization(String v) { this.workAuthorization = v; }
    public String getTargetTitles() { return targetTitles; }
    public void setTargetTitles(String v) { this.targetTitles = v; }
    public String getSkills() { return skills; }
    public void setSkills(String v) { this.skills = v; }
    public String getExcludeTitleWords() { return excludeTitleWords; }
    public void setExcludeTitleWords(String v) { this.excludeTitleWords = v; }
    public String getLocations() { return locations; }
    public void setLocations(String v) { this.locations = v; }
    public String getGreenhouseBoards() { return greenhouseBoards; }
    public void setGreenhouseBoards(String v) { this.greenhouseBoards = v; }
    public String getLeverCompanies() { return leverCompanies; }
    public void setLeverCompanies(String v) { this.leverCompanies = v; }
    public String getRemotiveSearches() { return remotiveSearches; }
    public void setRemotiveSearches(String v) { this.remotiveSearches = v; }
    public String getResumeFileName() { return resumeFileName; }
    public void setResumeFileName(String v) { this.resumeFileName = v; }
    public Instant getResumeUploadedAt() { return resumeUploadedAt; }
    public void setResumeUploadedAt(Instant v) { this.resumeUploadedAt = v; }
    public String getResumeText() { return resumeText; }
    public void setResumeText(String v) { this.resumeText = v; }
    public String getSmtpEmail() { return smtpEmail; }
    public void setSmtpEmail(String v) { this.smtpEmail = v; }
    public String getSmtpPassword() { return smtpPassword; }
    public void setSmtpPassword(String v) { this.smtpPassword = v; }
    public Integer getDailySendLimit() { return dailySendLimit; }
    public void setDailySendLimit(Integer v) { this.dailySendLimit = v; }
    public String getOllamaUrl() { return ollamaUrl; }
    public void setOllamaUrl(String v) { this.ollamaUrl = v; }
    public String getOllamaModel() { return ollamaModel; }
    public void setOllamaModel(String v) { this.ollamaModel = v; }
}
