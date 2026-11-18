package com.prreview.agent.service;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ReviewWorkerTest {
    @Test
    void rejectsAttemptsToDisablePublicationBeforeOpeningDatabase() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReviewWorker.run(new String[] {"--analyze-only"}));
        assertThrows(
                IllegalArgumentException.class, () -> ReviewWorker.run(new String[] {"--unknown"}));
    }
}
