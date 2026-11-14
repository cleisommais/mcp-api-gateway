package dev.cleisommais.mcp.f6;

import dev.cleisommais.mcp.f2.Json;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Conservative per-attempt reservations survive CLI restarts, including ambiguous failures. */
public final class CampaignBudget {
    private final Path journal;
    private final BigDecimal limit;

    public CampaignBudget(Path journal, BigDecimal limit) {
        if (limit.signum() <= 0 || limit.compareTo(BigDecimal.TEN) > 0) {
            throw new IllegalArgumentException("Set an explicit external campaign budget greater than 0 and at most USD 10");
        }
        this.journal = journal; this.limit = limit;
    }

    public synchronized String reserve(BigDecimal amount) throws Exception {
        if (amount.signum() <= 0) throw new IllegalArgumentException("Invalid call reservation");
        String id = UUID.randomUUID().toString();
        update(state -> {
            if (Boolean.TRUE.equals(state.get("blocked"))) throw new IOException("Campaign journal requires manual reconciliation before any further call");
            var attempts = new ArrayList<>(Json.list(state.get("attempts")));
            BigDecimal total = attempts.stream().map(Json::map).map(row -> new BigDecimal(row.get("reservedUsd").toString()))
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (total.add(amount).compareTo(limit) > 0) throw new IOException("External campaign reservation limit reached; no API call was sent");
            attempts.add(Map.of("id", id, "reservedUsd", amount.toPlainString(), "outcome", "reserved-or-unknown"));
            state.put("attempts", attempts);
        });
        return id;
    }

    public synchronized void record(String id, String outcome, Map<String, Object> usage) throws Exception {
        update(state -> {
            boolean found = false;
            for (Object value : Json.list(state.get("attempts"))) {
                var row = Json.map(value);
                if (id.equals(row.get("id"))) { row.put("outcome", outcome); row.put("usage", usage); found = true; }
            }
            if (!found) throw new IOException("Missing campaign reservation");
            if (outcome.equals("reservation-exceeded")) state.put("blocked", true);
        });
    }

    private interface Change { void apply(Map<String, Object> state) throws Exception; }
    private void update(Change change) throws Exception {
        Files.createDirectories(journal.toAbsolutePath().getParent());
        try (var channel = FileChannel.open(journal, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
             var lock = channel.lock()) {
            if (channel.size() > 2_000_000) throw new IOException("Campaign journal is too large");
            var bytes = ByteBuffer.allocate((int) channel.size());
            while (bytes.hasRemaining()) { if (channel.read(bytes) < 0) break; }
            var state = channel.size() == 0 ? new LinkedHashMap<String, Object>(Map.of("version", 1, "attempts", new ArrayList<>()))
                    : Json.object(new String(bytes.array(), StandardCharsets.UTF_8));
            if (!Integer.valueOf(1).equals(state.get("version"))) throw new IOException("Unsupported campaign journal");
            change.apply(state);
            byte[] encoded = (Json.write(state) + "\n").getBytes(StandardCharsets.UTF_8);
            channel.position(0); channel.truncate(0);
            var buffer = ByteBuffer.wrap(encoded); while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
}
