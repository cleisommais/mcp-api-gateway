package com.prreview.support;

import com.prreview.domain.model.ReviewEvent;

import java.time.Instant;
import java.util.UUID;

/** Shared fixture data; repository/publication tests do not depend on another test class. */
public final class GitHubFixture {
    public static final String REPOSITORY = "owner/private-repo";
    public static final String BASE = "a".repeat(40);
    public static final String HEAD = "b".repeat(40);

    private GitHubFixture() {}

    public static ReviewEvent event() {
        return new ReviewEvent(
                UUID.randomUUID().toString(),
                "a".repeat(64),
                "pull_request",
                "opened",
                17,
                REPOSITORY,
                1,
                42,
                BASE,
                HEAD,
                "main",
                Instant.parse("2026-11-13T20:00:00Z"),
                true,
                "accepted");
    }
}
