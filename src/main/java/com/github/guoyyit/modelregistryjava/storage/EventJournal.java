package com.github.guoyyit.modelregistryjava.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Append-only JSONL event journal backed by one file.
 *
 * <p>Each line is a self-contained JSON object:
 * <pre>{ "seq": 12, "type": "REGISTERED", "model": "...", "version": 3,
 *       "at": "2026-09-27T10:00:00Z", "payload": { ... } }</pre>
 *
 * <p>Appends are serialized by an intrinsic lock and force-fsynced before returning, so once
 * an append returns the record survives a process crash or OS-level crash (on filesystems that
 * honour fsync). A torn final line (a crash mid-write) is detected because it is not valid
 * JSON and is ignored on replay; only fully written records are ever visible.
 */
public class EventJournal {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private final Path journalFile;
    private final ObjectMapper mapper;
    private final Object appendLock = new Object();
    private long nextSeq = 1;
    private boolean initialized;

    public EventJournal(Path journalFile) {
        this.journalFile = journalFile;
        this.mapper = new ObjectMapper();
        this.mapper.findAndRegisterModules();
    }

    public void init() throws IOException {
        synchronized (appendLock) {
            if (initialized) {
                return;
            }
            Files.createDirectories(journalFile.getParent());
            if (Files.notExists(journalFile)) {
                Files.createFile(journalFile);
            }
            long max = 0;
            for (Record r : readAllInternal()) {
                max = Math.max(max, r.seq());
            }
            nextSeq = max + 1;
            initialized = true;
        }
    }

    /** Durably append an event and return its assigned sequence number. */
    public long append(String type, String model, Long version, Map<String, Object> payload)
            throws IOException {
        return append(type, model, version, payload, Instant.now());
    }

    /** Durably append an event stamped with the supplied instant. */
    public long append(String type, String model, Long version, Map<String, Object> payload,
                       Instant at) throws IOException {
        synchronized (appendLock) {
            if (!initialized) {
                init();
            }
            long seq = nextSeq++;
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("seq", seq);
            record.put("type", type);
            record.put("model", model);
            record.put("version", version);
            record.put("at", at.toString());
            record.put("payload", payload == null ? Map.of() : payload);
            byte[] line;
            try {
                line = mapper.writeValueAsBytes(record);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            byte[] withNewline = new byte[line.length + 1];
            System.arraycopy(line, 0, withNewline, 0, line.length);
            withNewline[line.length] = '\n';
            try (var ch = java.nio.channels.FileChannel.open(journalFile,
                    StandardOpenOption.WRITE, StandardOpenOption.APPEND)) {
                java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(withNewline);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                ch.force(true);
            }
            return seq;
        }
    }

    /** Read all complete records in sequence order. */
    public List<Record> readAll() throws IOException {
        synchronized (appendLock) {
            return readAllInternal();
        }
    }

    private List<Record> readAllInternal() throws IOException {
        List<Record> records = new ArrayList<>();
        if (Files.notExists(journalFile)) {
            return records;
        }
        long lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(journalFile, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank()) {
                    continue;
                }
                try {
                    Map<String, Object> map = mapper.readValue(line, MAP_TYPE);
                    records.add(toRecord(map));
                } catch (IOException badJson) {
                    // A torn trailing line from a crash mid-append. Only safe to ignore if it
                    // really is the last line; anything earlier means corruption we surface.
                    if (reader.readLine() == null) {
                        break;
                    }
                    throw new IOException("Corrupt journal record at line " + lineNo, badJson);
                }
            }
        }
        return records;
    }

    @SuppressWarnings("unchecked")
    private Record toRecord(Map<String, Object> map) {
        long seq = ((Number) map.getOrDefault("seq", 0L)).longValue();
        String type = (String) map.get("type");
        String model = (String) map.get("model");
        Object versionObj = map.get("version");
        Long version = versionObj == null ? null : ((Number) versionObj).longValue();
        Instant at = map.get("at") == null ? Instant.now() : Instant.parse((String) map.get("at"));
        Object payloadObj = map.get("payload");
        Map<String, Object> payload = payloadObj instanceof Map
                ? (Map<String, Object>) payloadObj
                : Map.of();
        return new Record(seq, type, model, version, at, payload);
    }

    /** One journal record. */
    public record Record(long seq, String type, String model, Long version,
                         Instant at, Map<String, Object> payload) {
    }
}
