package com.github.guoyyit.modelregistryjava.registry.storage;

import com.github.guoyyit.modelregistryjava.registry.AuditEvent;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only audit journal.
 *
 * <p>Every lifecycle decision (publish, promote, rollback, retire, delete,
 * recover) is appended here <em>before</em> the corresponding state change is
 * made visible. The journal is never truncated; it is the permanent audit
 * trail. Each line is one compact JSON event and the file is fsynced per
 * append, so an event either survives a crash whole or is absent.
 *
 * <p>The in-process append lock also gives a total, durable ordering and
 * gap-free sequence numbers.
 */
@Component
public class Journal {

    private final PathLayout paths;
    private final JsonCodec json;
    private final AtomicFiles atomicFiles;

    /** Cached high-water sequence; initialized lazily from disk. */
    private long lastSeq = -1L;

    public Journal(PathLayout paths, JsonCodec json, AtomicFiles atomicFiles) {
        this.paths = paths;
        this.json = json;
        this.atomicFiles = atomicFiles;
    }

    private synchronized long lastSequence() {
        if (lastSeq >= 0) {
            return lastSeq;
        }
        long maxSeq = 0L;
        Path file = paths.journal();
        if (Files.exists(file)) {
            try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    AuditEvent event = json.read(line, AuditEvent.class);
                    if (event.seq() > maxSeq) {
                        maxSeq = event.seq();
                    }
                }
            } catch (IOException e) {
                throw new UncheckedIOException("Failed to scan journal", e);
            }
        }
        lastSeq = maxSeq;
        return lastSeq;
    }

    /** Append an event, assigning the next gap-free sequence number. */
    public synchronized AuditEvent append(long at, String action, String model,
                                          String version, String detail) {
        try {
            atomicFiles.ensureDirectory(paths.root());
            long seq = lastSequence() + 1;
            AuditEvent event = new AuditEvent(seq, at, action, model, version,
                    detail == null ? "" : detail);
            // compact single-line JSON keeps the journal line-oriented
            String line = json.write(event).replaceAll("\\R", "") + System.lineSeparator();
            try (FileChannel ch = FileChannel.open(paths.journal(),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                ch.write(java.nio.ByteBuffer.wrap(line.getBytes(StandardCharsets.UTF_8)));
                ch.force(true);
            }
            atomicFiles.fsyncDirectory(paths.root());
            lastSeq = seq;
            return event;
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to append journal event: " + action, e);
        }
    }

    /** Read the complete audit trail in sequence order. */
    public List<AuditEvent> readAll() {
        List<AuditEvent> events = new ArrayList<>();
        Path file = paths.journal();
        if (!Files.exists(file)) {
            return events;
        }
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.isBlank()) {
                    events.add(json.read(line, AuditEvent.class));
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read journal", e);
        }
        events.sort((a, b) -> Long.compare(a.seq(), b.seq()));
        return events;
    }
}
