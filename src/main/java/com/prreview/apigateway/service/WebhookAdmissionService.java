package com.prreview.apigateway.service;

import com.prreview.apigateway.dto.CreateReviewRequest;
import com.prreview.common.json.Json;
import com.prreview.domain.model.WebhookRequest;
import com.prreview.persistence.repository.PostgresJobRepository;
import com.prreview.platform.bitbucket.client.BitbucketApiClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Durable admission of authenticated callbacks; no scheduled remote repository scans. */
public final class WebhookAdmissionService implements AutoCloseable {
    private final PostgresJobRepository jobs;
    private final ReviewRequestService reviews;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();

    public WebhookAdmissionService(PostgresJobRepository jobs, ReviewRequestService reviews) {
        this.jobs = jobs;
        this.reviews = reviews;
        executor.scheduleWithFixedDelay(this::drain, 0, 1, TimeUnit.SECONDS);
    }

    public boolean accept(WebhookRequest request) throws Exception {
        return jobs.acceptWebhook(request);
    }

    private void admit(WebhookRequest request, int number, String head) throws Exception {
        UUID id =
                UUID.nameUUIDFromBytes(
                        (request.platform() + ":" + request.delivery() + ":" + number)
                                .getBytes(StandardCharsets.UTF_8));
        var receipt =
                reviews.webhook(
                        new CreateReviewRequest(request.platform(), request.repository(), number),
                        id,
                        request.payloadHash(),
                        request.event(),
                        request.action(),
                        head);
        System.out.println(
                "Webhook "
                        + request.platform()
                        + "; delivery="
                        + request.delivery()
                        + "; event="
                        + request.event()
                        + "; action="
                        + request.action()
                        + "; repository="
                        + request.repository()
                        + "; number="
                        + number
                        + "; head="
                        + head
                        + "; duplicate="
                        + receipt.duplicate()
                        + "; job="
                        + receipt.jobId());
    }

    private void process(WebhookRequest request) throws Exception {
        if (request.number() > 0) {
            admit(request, request.number(), request.head());
            return;
        }
        var client = BitbucketApiClient.environment(request.repository(), false);
        String query =
                URLEncoder.encode(
                        "source.branch.name=\"" + request.branch() + "\"", StandardCharsets.UTF_8);
        for (int page = 1; page <= 10; page++) {
            var result =
                    Json.map(
                            client.get(
                                    client.repositoryPath()
                                            + "/pullrequests?state=OPEN&pagelen=50&q="
                                            + query
                                            + "&page="
                                            + page));
            for (var entry : Json.list(result.get("values"))) {
                var pr = Json.map(entry);
                var source = Json.map(pr.get("source"));
                if (Boolean.TRUE.equals(pr.get("draft"))
                        || !request.repository()
                                .equals(Json.text(Json.map(source.get("repository")), "full_name")))
                    continue;
                // Pin to the pushed commit, never to a temporarily stale PR-list hash.
                admit(request, ((Number) pr.get("id")).intValue(), request.head());
            }
            if (!result.containsKey("next")) return;
        }
        throw new IllegalStateException("PR enumeration exceeded limit");
    }

    private void drain() {
        try {
            var request = jobs.claimWebhook();
            if (request == null) return;
            String failure = null;
            try {
                process(request);
            } catch (Exception error) {
                failure = error.getClass().getSimpleName();
                System.err.println(
                        "Webhook admission retry; platform="
                                + request.platform()
                                + "; delivery="
                                + request.delivery()
                                + "; reason="
                                + failure);
            }
            jobs.finishWebhook(request.id(), failure);
        } catch (Exception error) {
            System.err.println("Webhook inbox unavailable; " + error.getClass().getSimpleName());
        }
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
