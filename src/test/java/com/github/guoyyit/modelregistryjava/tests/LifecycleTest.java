package com.github.guoyyit.modelregistryjava.tests;

import com.github.guoyyit.modelregistryjava.domain.ModelVersion;
import com.github.guoyyit.modelregistryjava.domain.Stage;
import com.github.guoyyit.modelregistryjava.exception.ConflictException;
import com.github.guoyyit.modelregistryjava.service.ModelRegistryService;
import com.github.guoyyit.modelregistryjava.service.RegisterResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LifecycleTest {

    static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    static RegisterResult register(ModelRegistryService svc, String model, String content)
            throws Exception {
        String checksum = sha256(content);
        return svc.register(model, -1, checksum, "application/octet-stream",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void newVersionStartsRegisteredAndIsImmutable(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        ModelVersion v = register(svc, "llm", "weights-a").version();
        assertEquals(Stage.REGISTERED, v.getStage());
        assertEquals(sha256("weights-a"), v.getChecksum());

        ModelVersion fetched = svc.getVersion("llm", v.getVersion());
        assertNotSame(v, fetched);
        assertEquals(v.getChecksum(), fetched.getChecksum());
        assertEquals(v.getSize(), fetched.getSize());
    }

    @Test
    void cannotSkipCandidateStage(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        long v = register(svc, "llm", "weights-a").version().getVersion();
        // REGISTERED -> PRODUCTION directly must be rejected
        assertThrows(ConflictException.class, () -> svc.promoteToProduction("llm", v));
        // after candidate, production works
        svc.promoteToCandidate("llm", v);
        assertEquals(Stage.PRODUCTION, svc.promoteToProduction("llm", v).getStage());
        // already production cannot be promoted to candidate again
        assertThrows(ConflictException.class, () -> svc.promoteToCandidate("llm", v));
    }

    @Test
    void promotingNewProductionDemotesPrevious(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        long v1 = register(svc, "llm", "weights-1").version().getVersion();
        long v2 = register(svc, "llm", "weights-2").version().getVersion();
        svc.promoteToCandidate("llm", v1);
        svc.promoteToProduction("llm", v1);
        svc.promoteToCandidate("llm", v2);
        svc.promoteToProduction("llm", v2);

        assertEquals(v2, svc.getCurrentProduction("llm").getVersion());
        assertEquals(Stage.CANDIDATE, svc.getVersion("llm", v1).getStage());
        assertEquals(Stage.PRODUCTION, svc.getVersion("llm", v2).getStage());
    }

    @Test
    void takenVersionCannotBeOverwritten(@TempDir Path home) throws Exception {
        ModelRegistryService svc = TestServices.newService(home);
        register(svc, "llm", "weights-1");
        assertThrows(ConflictException.class, () ->
                svc.register("llm", 1L, sha256("weights-2"), null,
                        new ByteArrayInputStream("weights-2".getBytes(StandardCharsets.UTF_8))));
        assertFalse(svc.getVersion("llm", 1).getChecksum().equals(sha256("weights-2")),
                "existing version content must not be overwritten");
    }
}
