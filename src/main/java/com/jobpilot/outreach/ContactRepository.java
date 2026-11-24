package com.jobpilot.outreach;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    List<Contact> findByJobIdOrderByConfidenceDescIdAsc(Long jobId);

    Optional<Contact> findByJobIdAndEmailIgnoreCase(Long jobId, String email);
}
