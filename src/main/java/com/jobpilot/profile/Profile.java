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

    // ---- Answers to common application questions (Profile tab → Application answers) ----
    private String country;
    private String school;
    private String degree;
    private String discipline;
    private String startYear;
    private String gradMonth;
    private String gradYear;
    private String cgpa;
    private String currentTitle;
    @Column(length = 500)
    private String primaryLanguage;
    @Column(length = 4000)
    private String openSourceLinks;
    private String fullTimeStart;
    private String internshipAvailable;
    private String onsiteOk;
    private String needsSponsorship;
    @Column(length = 1000)
    private String offerDeadlines;
    private String howHeard;
    private String codeforcesRating;
    private String codechefRating;
    private String preferredName;
    private String pronouns;
    @Column(length = 4000)
    private String customAnswers;

    // ---- Experience level and Autopilot (Autopilot tab) ----
    /** FRESHER or EXPERIENCED; empty = not set (no experience filtering). */
    private String experienceLevel;
    private Boolean autoShortlist;
    private Integer autoMinScore;
    private Boolean autoTailor;
    private Boolean autoFill;
    private Integer autoMaxPerRun;
    private Integer autoMaxWaiting;

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
    public String getCountry() { return country; }
    public void setCountry(String v) { this.country = v; }
    public String getSchool() { return school; }
    public void setSchool(String v) { this.school = v; }
    public String getDegree() { return degree; }
    public void setDegree(String v) { this.degree = v; }
    public String getDiscipline() { return discipline; }
    public void setDiscipline(String v) { this.discipline = v; }
    public String getStartYear() { return startYear; }
    public void setStartYear(String v) { this.startYear = v; }
    public String getGradMonth() { return gradMonth; }
    public void setGradMonth(String v) { this.gradMonth = v; }
    public String getGradYear() { return gradYear; }
    public void setGradYear(String v) { this.gradYear = v; }
    public String getCgpa() { return cgpa; }
    public void setCgpa(String v) { this.cgpa = v; }
    public String getCurrentTitle() { return currentTitle; }
    public void setCurrentTitle(String v) { this.currentTitle = v; }
    public String getPrimaryLanguage() { return primaryLanguage; }
    public void setPrimaryLanguage(String v) { this.primaryLanguage = v; }
    public String getOpenSourceLinks() { return openSourceLinks; }
    public void setOpenSourceLinks(String v) { this.openSourceLinks = v; }
    public String getFullTimeStart() { return fullTimeStart; }
    public void setFullTimeStart(String v) { this.fullTimeStart = v; }
    public String getInternshipAvailable() { return internshipAvailable; }
    public void setInternshipAvailable(String v) { this.internshipAvailable = v; }
    public String getOnsiteOk() { return onsiteOk; }
    public void setOnsiteOk(String v) { this.onsiteOk = v; }
    public String getNeedsSponsorship() { return needsSponsorship; }
    public void setNeedsSponsorship(String v) { this.needsSponsorship = v; }
    public String getOfferDeadlines() { return offerDeadlines; }
    public void setOfferDeadlines(String v) { this.offerDeadlines = v; }
    public String getHowHeard() { return howHeard; }
    public void setHowHeard(String v) { this.howHeard = v; }
    public String getCodeforcesRating() { return codeforcesRating; }
    public void setCodeforcesRating(String v) { this.codeforcesRating = v; }
    public String getCodechefRating() { return codechefRating; }
    public void setCodechefRating(String v) { this.codechefRating = v; }
    public String getPreferredName() { return preferredName; }
    public void setPreferredName(String v) { this.preferredName = v; }
    public String getPronouns() { return pronouns; }
    public void setPronouns(String v) { this.pronouns = v; }
    public String getCustomAnswers() { return customAnswers; }
    public void setCustomAnswers(String v) { this.customAnswers = v; }
    public String getExperienceLevel() { return experienceLevel; }
    public void setExperienceLevel(String v) { this.experienceLevel = v; }
    public boolean isAutoShortlist() { return Boolean.TRUE.equals(autoShortlist); }
    public void setAutoShortlist(Boolean v) { this.autoShortlist = v; }
    public int getAutoMinScore() { return autoMinScore == null ? 70 : Math.max(0, Math.min(100, autoMinScore)); }
    public void setAutoMinScore(Integer v) { this.autoMinScore = v; }
    public boolean isAutoTailor() { return Boolean.TRUE.equals(autoTailor); }
    public void setAutoTailor(Boolean v) { this.autoTailor = v; }
    public boolean isAutoFill() { return Boolean.TRUE.equals(autoFill); }
    public void setAutoFill(Boolean v) { this.autoFill = v; }
    /** Most jobs Autopilot takes on per run (tailoring takes ~2-3 minutes each). */
    public int getAutoMaxPerRun() { return autoMaxPerRun == null ? 5 : Math.max(1, Math.min(30, autoMaxPerRun)); }
    public void setAutoMaxPerRun(Integer v) { this.autoMaxPerRun = v; }
    /** Most filled-in forms left open waiting for your Submit, so Chrome windows don't pile up. */
    public int getAutoMaxWaiting() { return autoMaxWaiting == null ? 5 : Math.max(1, Math.min(20, autoMaxWaiting)); }
    public void setAutoMaxWaiting(Integer v) { this.autoMaxWaiting = v; }
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
