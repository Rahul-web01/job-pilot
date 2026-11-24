package com.jobpilot.source;

import java.util.List;

import com.jobpilot.profile.Profile;

/** One job platform. Add a new platform by adding a new @Component implementing this. */
public interface JobSource {

    String name();

    List<JobPosting> fetch(Profile profile);
}
