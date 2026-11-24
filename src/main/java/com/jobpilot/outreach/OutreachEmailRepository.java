package com.jobpilot.outreach;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OutreachEmailRepository extends JpaRepository<OutreachEmail, Long> {

    List<OutreachEmail> findByJobIdOrderByIdAsc(Long jobId);

    List<OutreachEmail> findAllByOrderByIdDesc();

    long countBySentAtAfter(Instant since);

    boolean existsByFollowUpOfId(Long id);

    boolean existsByJobIdAndContactIdAndFollowUpOfIdIsNull(Long jobId, Long contactId);
}
