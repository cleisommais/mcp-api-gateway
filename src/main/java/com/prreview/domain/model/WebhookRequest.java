package com.prreview.domain.model;

import java.util.UUID;

/** Normalized authenticated event, without raw body or authentication headers. */
public record WebhookRequest(
        UUID id,
        String platform,
        String delivery,
        String repository,
        int number,
        String branch,
        String head,
        String event,
        String action,
        String payloadHash) {}
