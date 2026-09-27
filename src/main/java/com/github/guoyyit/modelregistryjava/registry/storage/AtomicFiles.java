package com.github.guoyyit.modelregistryjava.registry.storage;

import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Atomic, crash-safe file primitives on the local filesystem.
 *
 * <p>All durable state is committed with the same sequence:
 * write a uniquely-named temp file in the same directory, fsync the file,
 * atomically {@code move} it onto the target, then fsync the parent directory.
 * A crash at any point leaves either the old file or the new one, never a
 * torn document.
 */
@Component
public class AtomicFiles {

    /** Write bytes to {@code target} via temp-file + atomic move + fsync. */
    public void writeAtomically(Path target, byte[] bytes) throws IOException {
        ensureDirectory(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp."
                + Long.toHexString(System.nanoTime()) + Thread.currentThread().getId());
        try (FileChannel ch = FileChannel.open(tmp,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ch.write(java.nio.ByteBuffer.wrap(bytes));
            ch.force(true);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicFailure) {
            // fall back to a non-atomic replace only on filesystems without ATOMIC_MOVE
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
        fsyncDirectory(target.getParent());
    }

    /** Ensure a directory exists. */
    public void ensureDirectory(Path dir) throws IOException {
        if (dir == null) {
            return;
        }
        Files.createDirectories(dir);
    }

    /** fsync a directory (required to persist file creation/removal). */
    public void fsyncDirectory(Path dir) throws IOException {
        if (dir == null || !Files.isDirectory(dir)) {
            return;
        }
        try (FileChannel ch = FileChannel.open(dir, StandardOpenOption.READ)) {
            ch.force(true);
        } catch (IOException ignored) {
            // some platforms do not support fsync on a directory; best effort
        }
    }
}
