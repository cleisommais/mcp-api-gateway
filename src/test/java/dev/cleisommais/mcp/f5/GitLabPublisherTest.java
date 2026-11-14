package dev.cleisommais.mcp.f5;

import dev.cleisommais.mcp.f2.Json;
import dev.cleisommais.mcp.f4.ReviewPublisher;
import org.junit.jupiter.api.Test;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static dev.cleisommais.mcp.f5.GitLabFixture.*;
import static org.junit.jupiter.api.Assertions.*;

class GitLabPublisherTest {
    private static final String MARKER = "<!-- mcp-review-poc:gitlab:42:1 -->";
    static Map<String, Object> report() {
        var result = new HashMap<String, Object>();
        result.put("platform", "gitlab"); result.put("repository", REPO); result.put("number", 1);
        result.put("headSha", HEAD); result.put("baseSha", BASE); result.put("baseBranch", "main");
        result.put("current", true); result.put("status", "partial"); result.put("enumeratedFiles", 2);
        result.put("model", "fixture-model"); result.put("findings", List.of());
        result.put("coverage", List.of(Map.of("file", "File.java", "status", "analyzed"), Map.of("file", "missing.java", "status", "pending")));
        return result;
    }
    private static Map<String, Object> note(long id, long author, String body) {
        return Map.of("id", id, "author", Map.of("id", author), "body", body, "system", false);
    }
    @Test void createsThenUpdatesOnlyOwnedSummaryAndPreservesPartialCoverage() throws Exception {
        var notes = new ArrayList<Map<String, Object>>();
        notes.add(note(5, 99, MARKER + "\nspoofed"));
        var writes = new ArrayList<String>();
        try (var fixture = new GitLabFixture(exchange -> {
            String path = exchange.getRequestURI().toString();
            if (path.equals("/user")) return ok(Map.of("id", 7));
            if (path.contains("/notes")) {
                assertFalse(path.contains("order_by=id"), "GitLab notes only support created_at or updated_at ordering");
                if (exchange.getRequestMethod().equals("GET")) return ok(notes);
                writes.add(exchange.getRequestMethod() + " " + path);
                String body = Json.text(Json.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)), "body");
                notes.removeIf(n -> n.get("id").equals(8L)); notes.add(note(8, 7, body));
                return ok(Map.of("id", 8));
            }
            return ok(metadata("2"));
        })) {
            var publisher = new GitLabPublisher(fixture.access(true));
            assertEquals(8, publisher.publish(REPO, 1, report(), false, () -> {}));
            assertEquals(8, publisher.publish(REPO, 1, report(), true, () -> {}));
            assertEquals(List.of("POST /projects/42/merge_requests/1/notes", "PUT /projects/42/merge_requests/1/notes/8"), writes);
            String body = (String) notes.getLast().get("body");
            assertTrue(body.contains(HEAD)); assertTrue(body.contains("**partial**"));
            assertTrue(body.contains("Files requiring further analysis: 1"));
            assertEquals("spoofed", ((String) notes.getFirst().get("body")).substring(MARKER.length() + 1));
        }
    }

    @Test void reconcilesLostResponseWithoutSecondPostAndStopsIfNoMatchingBody() throws Exception {
        for (boolean committed : List.of(true, false)) {
            var notes = new ArrayList<Map<String, Object>>();
            var writes = new AtomicInteger();
            try (var fixture = new GitLabFixture(exchange -> {
                String path = exchange.getRequestURI().toString();
                if (path.equals("/user")) return ok(Map.of("id", 7));
                if (path.contains("/notes")) {
                    if (exchange.getRequestMethod().equals("GET")) return ok(notes);
                    writes.incrementAndGet();
                    String body = Json.text(Json.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)), "body");
                    if (committed) notes.add(note(8, 7, body.replace("\r", "").stripTrailing()));
                    return new Response(500, Map.of());
                }
                return ok(metadata("2"));
            })) {
                var publisher = new GitLabPublisher(fixture.access(true));
                if (committed) assertEquals(8, publisher.publish(REPO, 1, report(), false, () -> {}));
                else {
                    assertThrows(ReviewPublisher.Uncertain.class, () -> publisher.publish(REPO, 1, report(), false, () -> {}));
                    assertThrows(ReviewPublisher.Uncertain.class, () -> publisher.publish(REPO, 1, report(), true, () -> {}));
                }
                assertEquals(1, writes.get());
            }
        }
    }

    @Test void refusesClosedDraftStaleRetargetedAndWrongReportBeforeWrite() throws Exception {
        for (String field : List.of("state", "draft", "target_branch", "head")) {
            try (var fixture = new GitLabFixture(exchange -> {
                var value = metadata("2");
                switch (field) {
                    case "state" -> value.put("state", "closed");
                    case "draft" -> value.put("draft", true);
                    case "target_branch" -> value.put("target_branch", "other");
                    default -> { value.put("sha", "c".repeat(40)); value.put("diff_refs", Map.of("base_sha", BASE, "start_sha", BASE, "head_sha", "c".repeat(40))); }
                }
                return ok(value);
            })) {
                assertThrows(ReviewPublisher.Outdated.class, () -> new GitLabPublisher(fixture.access(true)).publish(REPO, 1, report(), false, () -> fail("Journal written")));
                assertEquals(1, fixture.requests.size());
            }
        }
        try (var fixture = new GitLabFixture(exchange -> { fail("Wrong report reached network"); return null; })) {
            var wrong = report(); wrong.put("platform", "github");
            assertThrows(IllegalArgumentException.class, () -> new GitLabPublisher(fixture.access(true)).publish(REPO, 1, wrong, false, () -> {}));
        }
    }

    @Test void paginatesNotesAndDeniesRepeatedPagesBeforeWriting() throws Exception {
        for (boolean repeated : List.of(true, false)) {
            var notes = new ArrayList<Map<String, Object>>();
            for (int i = 1; i <= 100; i++) notes.add(note(i, 99, "human note"));
            var writes = new AtomicInteger();
            try (var fixture = new GitLabFixture(exchange -> {
                String path = exchange.getRequestURI().toString();
                if (path.equals("/user")) return ok(Map.of("id", 7));
                if (path.contains("/notes")) {
                    if (!exchange.getRequestMethod().equals("GET")) { writes.incrementAndGet(); return ok(Map.of("id", 101)); }
                    if (path.contains("page=1&") || repeated) return ok(notes);
                    return ok(List.of(note(101, 7, MARKER + "\nprevious")));
                }
                return ok(metadata("2"));
            })) {
                var publisher = new GitLabPublisher(fixture.access(true));
                if (repeated) assertThrows(java.io.IOException.class, () -> publisher.publish(REPO, 1, report(), false, () -> {}));
                else assertEquals(101, publisher.publish(REPO, 1, report(), false, () -> {}));
                assertEquals(repeated ? 0 : 1, writes.get());
            }
        }
    }

    @Test void marksSummaryOutdatedWhenMrChangesDuringWrite() throws Exception {
        var state = new AtomicReference<>(metadata("2"));
        var bodies = new ArrayList<String>();
        try (var fixture = new GitLabFixture(exchange -> {
            String path = exchange.getRequestURI().toString();
            if (path.equals("/user")) return ok(Map.of("id", 7));
            if (path.contains("/notes")) {
                if (exchange.getRequestMethod().equals("GET")) return ok(List.of());
                bodies.add(Json.text(Json.object(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)), "body"));
                var changed = metadata("2"); changed.put("draft", true); state.set(changed);
                return ok(Map.of("id", 8));
            }
            return ok(state.get());
        })) {
            assertThrows(ReviewPublisher.Outdated.class, () -> new GitLabPublisher(fixture.access(true)).publish(REPO, 1, report(), false, () -> {}));
            assertEquals(2, bodies.size()); assertTrue(bodies.getLast().contains("Outdated POC review"));
        }
    }

    @Test void neutralizesGitLabQuickActionsAndMentionsInUntrustedFindingText() {
        var report = report();
        report.put("findings", List.of(Map.of("file", "A.java", "line", 1, "severity", "MEDIUM", "explanation", "bug\n/merge\n@all")));
        String body = ReviewPublisher.summary(HEAD, report, MARKER);
        assertFalse(body.contains("\n/merge")); assertFalse(body.contains("@all"));
    }
}
