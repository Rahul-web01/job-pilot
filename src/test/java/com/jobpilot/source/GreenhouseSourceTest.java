package com.jobpilot.source;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class GreenhouseSourceTest {

    @Test
    void dropsBoardWordsFromCompanyNames() {
        assertThat(GreenhouseSource.cleanCompany("Rubrik Job Board")).isEqualTo("Rubrik");
        assertThat(GreenhouseSource.cleanCompany("HackerRank Careers")).isEqualTo("HackerRank");
        assertThat(GreenhouseSource.cleanCompany("Stripe")).isEqualTo("Stripe");
        assertThat(GreenhouseSource.cleanCompany("Jobs")).isEqualTo("Jobs");
    }
}
