package com.prreview.tools.evaluation;

import static org.junit.jupiter.api.Assertions.*;

import com.prreview.agent.service.ReviewEngine;
import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.model.ModelGeneration;
import com.prreview.domain.port.ChangeSource;
import com.prreview.domain.port.ReviewModel;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ComparisonControlTest {
    @Test
    void loadsCapturedFixtureWithoutClaimingLiveState(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        var snapshot =
                new ChangeSnapshot(
                        "github",
                        "owner/repo",
                        1,
                        "",
                        "",
                        "a".repeat(40),
                        "b".repeat(40),
                        "owner/repo",
                        "open",
                        0,
                        true,
                        List.of());
        var path = directory.resolve("capture.json");
        java.nio.file.Files.writeString(
                path,
                Json.write(
                        Map.of(
                                "snapshot",
                                snapshot,
                                "contents",
                                Map.of("File.java", "captured"),
                                "contextErrors",
                                Map.of(),
                                "capturedAt",
                                "2026-11-13T00:00:00Z")));
        try (var source = CapturedReviewFixture.FrozenSource.fromCapture(path)) {
            assertEquals(snapshot, source.snapshot());
            assertEquals("captured", source.fileAtCommit(snapshot.headSha(), "File.java"));
            assertFalse(source.isCurrent(snapshot));
        }
        var legacy = Json.object(java.nio.file.Files.readString(path));
        var fields = Json.map(legacy.get("snapshot"));
        fields.remove("draft");
        fields.remove("baseBranch");
        java.nio.file.Files.writeString(path, Json.write(legacy));
        try (var source = CapturedReviewFixture.FrozenSource.fromCapture(path)) {
            assertFalse(source.snapshot().draft());
            assertEquals("", source.snapshot().baseBranch());
            assertFalse(source.isCurrent(source.snapshot()));
        }
    }

    @Test
    void reusesExactInputsAndRejectsChangedContext() throws Exception {
        var inputs = new ArrayList<CapturedReviewFixture.Input>();
        var observed = new ArrayList<String>();
        ReviewModel model =
                new ReviewModel() {
                    @Override
                    public ModelGeneration generate(
                            String system, String context, Map<String, Object> schema) {
                        observed.add(system + context);
                        return new ModelGeneration("{}", 1, 1, 1);
                    }

                    @Override
                    public String name() {
                        return "fake";
                    }
                };
        new CapturedReviewFixture.ControlledModel(model, inputs, true)
                .generate("policy", "same data", ReviewEngine.SCHEMA);
        new CapturedReviewFixture.ControlledModel(model, inputs, false)
                .generate("policy", "same data", ReviewEngine.SCHEMA);
        assertEquals(observed.getFirst(), observed.getLast());
        assertThrows(
                IllegalStateException.class,
                () ->
                        new CapturedReviewFixture.ControlledModel(model, inputs, false)
                                .generate("policy", "different data", ReviewEngine.SCHEMA));
        assertEquals(2, observed.size());
    }

    @Test
    void replayReadsOnlyCapturedShaAndNeverRequeriesSource() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var closed = new java.util.concurrent.atomic.AtomicInteger();
        var live =
                new ChangeSource() {
                    @Override
                    public ChangeSnapshot snapshot() {
                        calls.incrementAndGet();
                        return new ChangeSnapshot(
                                "github",
                                "owner/repo",
                                1,
                                "",
                                "",
                                "a".repeat(40),
                                "b".repeat(40),
                                "owner/repo",
                                "open",
                                0,
                                true,
                                List.of());
                    }

                    @Override
                    public String fileAtCommit(String sha, String path) {
                        calls.incrementAndGet();
                        return "content";
                    }

                    @Override
                    public boolean isCurrent(ChangeSnapshot snapshot) {
                        calls.incrementAndGet();
                        return true;
                    }

                    @Override
                    public void close() {
                        closed.incrementAndGet();
                    }
                };
        try (var frozen = new CapturedReviewFixture.FrozenSource(live)) {
            var snapshot = frozen.snapshot();
            assertEquals("content", frozen.fileAtCommit(snapshot.headSha(), "File.java"));
            assertTrue(frozen.isCurrent(snapshot));
            frozen.freeze();
            int before = calls.get();
            assertEquals(snapshot, frozen.snapshot());
            assertEquals("content", frozen.fileAtCommit(snapshot.headSha(), "File.java"));
            assertTrue(frozen.isCurrent(snapshot));
            assertThrows(
                    java.io.IOException.class,
                    () -> frozen.fileAtCommit(snapshot.headSha(), "Other.java"));
            assertThrows(
                    SecurityException.class,
                    () -> frozen.fileAtCommit("c".repeat(40), "File.java"));
            assertEquals(before, calls.get());
        }
        assertEquals(1, closed.get());
    }
}
