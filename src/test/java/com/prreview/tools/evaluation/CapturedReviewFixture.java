package com.prreview.tools.evaluation;

import com.prreview.common.json.Json;
import com.prreview.domain.model.ChangeSnapshot;
import com.prreview.domain.model.ModelGeneration;
import com.prreview.domain.port.ChangeSource;
import com.prreview.domain.port.ReviewModel;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Public-data experiment: change one variable at a time, preserving the exact model inputs. */
public final class CapturedReviewFixture {
    private CapturedReviewFixture() {}

    record Input(String system, String context, Map<String, Object> schema, String sha256) {}

    static final class ControlledModel implements ReviewModel {
        private final ReviewModel delegate;
        private final List<Input> inputs;
        private final boolean capture;
        private int index;

        ControlledModel(ReviewModel delegate, List<Input> inputs, boolean capture) {
            this.delegate = delegate;
            this.inputs = inputs;
            this.capture = capture;
        }

        @Override
        public ModelGeneration generate(String system, String context, Map<String, Object> schema)
                throws Exception {
            String hash =
                    HexFormat.of()
                            .formatHex(
                                    MessageDigest.getInstance("SHA-256")
                                            .digest(
                                                    (system
                                                                    + "\n"
                                                                    + context
                                                                    + "\n"
                                                                    + Json.write(schema))
                                                            .getBytes(StandardCharsets.UTF_8)));
            if (capture) inputs.add(new Input(system, context, schema, hash));
            else {
                if (index >= inputs.size() || !hash.equals(inputs.get(index).sha256()))
                    throw new IllegalStateException("Comparison input changed");
                var original = inputs.get(index);
                system = original.system();
                context = original.context();
                schema = original.schema();
            }
            index++;
            return delegate.generate(system, context, schema);
        }

        @Override
        public String name() {
            return delegate.name();
        }
    }

    static final class FrozenSource implements ChangeSource {
        private final ChangeSource delegate;
        private ChangeSnapshot snapshot;
        private final Map<String, String> contents = new LinkedHashMap<>();
        private final Map<String, String> errors = new LinkedHashMap<>();
        private boolean frozen;
        private boolean current;

        FrozenSource(ChangeSource delegate) {
            this.delegate = delegate;
        }

        static FrozenSource fromCapture(Path path) throws Exception {
            var record = Json.object(Files.readString(path));
            var result = new FrozenSource(null);
            var snapshot = new LinkedHashMap<>(Json.map(record.get("snapshot")));
            // Older captures omit draft/target-branch fields; replay never establishes current
            // state.
            snapshot.putIfAbsent("draft", false);
            snapshot.putIfAbsent("baseBranch", "");
            result.snapshot =
                    Json.read(
                            Json.write(snapshot),
                            new io.modelcontextprotocol.json.TypeRef<ChangeSnapshot>() {});
            Json.map(record.get("contents"))
                    .forEach((key, value) -> result.contents.put(key, (String) value));
            Json.map(record.get("contextErrors"))
                    .forEach((key, value) -> result.errors.put(key, (String) value));
            result.frozen = true;
            result.current = false;
            return result;
        }

        @Override
        public ChangeSnapshot snapshot() throws Exception {
            if (snapshot == null) snapshot = delegate.snapshot();
            return snapshot;
        }

        @Override
        public String fileAtCommit(String sha, String path) throws Exception {
            if (!snapshot().headSha().equals(sha))
                throw new SecurityException("Replay requires pinned SHA");
            if (errors.containsKey(path)) throw new IOException(errors.get(path));
            if (contents.containsKey(path)) return contents.get(path);
            if (frozen) throw new IOException("Context was not present in captured input");
            try {
                String content = delegate.fileAtCommit(sha, path);
                contents.put(path, content);
                return content;
            } catch (Exception error) {
                errors.put(path, error.getClass().getSimpleName());
                throw error;
            }
        }

        @Override
        public boolean isCurrent(ChangeSnapshot snapshot) throws Exception {
            if (!frozen) current = delegate.isCurrent(snapshot);
            return current;
        }

        void freeze() {
            if (!frozen) {
                frozen = true;
                delegate.close();
            }
        }

        @Override
        public void close() {
            freeze();
        }
    }
}
