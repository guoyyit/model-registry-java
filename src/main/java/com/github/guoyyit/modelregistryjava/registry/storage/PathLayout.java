package com.github.guoyyit.modelregistryjava.registry.storage;

import com.github.guoyyit.modelregistryjava.registry.config.RegistryProperties;
import com.github.guoyyit.modelregistryjava.registry.exception.InvalidRequestException;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * On-disk layout:
 * <pre>
 *   root/
 *     journal.log
 *     models/&lt;model&gt;/versions/&lt;version&gt;/meta.json
 *     models/&lt;model&gt;/versions/&lt;version&gt;/content.bin
 *     uploads/&lt;uploadId&gt;.json          (marker)
 *     uploads/&lt;uploadId&gt;.bin           (partial content)
 * </pre>
 *
 * Model and version identifiers are validated/sanitized before being used as
 * path segments so they can never escape the storage root.
 */
@Component
public class PathLayout {

    private final RegistryProperties properties;
    private final Path root;

    public PathLayout(RegistryProperties properties) {
        this.properties = properties;
        this.root = Paths.get(properties.getStoragePath()).toAbsolutePath().normalize();
    }

    private static String safeSegment(String raw, String what) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidRequestException(what + " must not be empty");
        }
        String s = raw.trim();
        if (s.equals(".") || s.equals("..") || s.contains("/") || s.contains("\\")
                || s.contains("\0")) {
            throw new InvalidRequestException("Illegal " + what + ": " + raw);
        }
        if (!s.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new InvalidRequestException(
                    what + " may only contain letters, digits, '.', '_' or '-' (max 128): " + raw);
        }
        return s;
    }

    public String safeModel(String model) {
        return safeSegment(model, "model");
    }

    public String safeVersion(String version) {
        return safeSegment(version, "version");
    }

    /** Upload ids are generated server-side as UUIDs; still validated. */
    public String safeUploadId(String uploadId) {
        return safeSegment(uploadId, "uploadId");
    }

    public Path root() {
        return root;
    }

    public Path journal() {
        return root.resolve("journal.log");
    }

    public Path versionsRoot() {
        return root.resolve("models");
    }

    public Path modelDir(String model) {
        return versionsRoot().resolve(safeModel(model));
    }

    public Path versionDir(String model, String version) {
        return modelDir(model).resolve("versions").resolve(safeVersion(version));
    }

    public Path metaFile(String model, String version) {
        return versionDir(model, version).resolve("meta.json");
    }

    public Path contentFile(String model, String version) {
        return versionDir(model, version).resolve("content.bin");
    }

    public Path uploadsDir() {
        return root.resolve("uploads");
    }

    public Path uploadMarker(String uploadId) {
        return uploadsDir().resolve(safeUploadId(uploadId) + ".json");
    }

    public Path uploadContent(String uploadId) {
        return uploadsDir().resolve(safeUploadId(uploadId) + ".bin");
    }
}
