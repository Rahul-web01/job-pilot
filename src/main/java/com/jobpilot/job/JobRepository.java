package com.jobpilot.job;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface JobRepository extends JpaRepository<Job, Long> {

    boolean existsByFingerprint(String fingerprint);

    Optional<Job> findByExternalKey(String externalKey);

    List<Job> findByStatusOrderByScoreDescFoundAtDesc(JobStatus status);

    List<Job> findByStatusInOrderByStatusChangedAtDesc(List<JobStatus> statuses);

    long countByStatus(JobStatus status);

    @Query("select j.source, count(j) from Job j group by j.source order by count(j) desc")
    List<Object[]> countBySource();

    @Query("select distinct j.source from Job j order by j.source")
    List<String> sources();
}
