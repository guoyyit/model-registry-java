package com.github.guoyyit.modelregistryjava.registry.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class RegistryApiTest {

    static Path storage;

    static {
        try {
            storage = Files.createTempDirectory("registry-api-");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("registry.storage-path", () -> storage.toString());
    }

    @Autowired
    private WebApplicationContext context;

    private final ObjectMapper mapper = new ObjectMapper();

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private String beginUpload(String model, String version, String token,
                               String checksum, long size) throws Exception {
        java.util.Map<String, Object> request = new java.util.LinkedHashMap<>();
        request.put("model", model);
        request.put("version", version);
        if (token != null) {
            request.put("clientToken", token);
        }
        request.put("expectedChecksum", checksum);
        request.put("contentType", "text/plain");
        request.put("expectedSize", size);
        MvcResult result = mvc().perform(post("/api/models/" + model + "/uploads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = mapper.readTree(result.getResponse().getContentAsString());
        return node.get("uploadId").asText();
    }

    private void publishViaHttp(String model, String version, String content, String token)
            throws Exception {
        byte[] data = content.getBytes(StandardCharsets.UTF_8);
        String uploadId = beginUpload(model, version, token, sha256(data), data.length);
        mvc().perform(post("/api/models/" + model + "/uploads/" + uploadId + "/content")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data))
                .andExpect(status().isNoContent());
        mvc().perform(post("/api/models/" + model + "/uploads/" + uploadId + "/complete"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version.state").value("REGISTERED"));
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        String model = "net-classifier";
        String content = "model-bytes-v1";
        publishViaHttp(model, "1.0.0", content, "http-token-1");

        mvc().perform(get("/api/models/" + model + "/versions/1.0.0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.state").value("REGISTERED"));
        mvc().perform(get("/api/models/" + model + "/versions/1.0.0/content"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Model-Checksum",
                        org.hamcrest.Matchers.startsWith("SHA-256:")))
                .andExpect(content().bytes(content.getBytes(StandardCharsets.UTF_8)));

        mvc().perform(post("/api/models/" + model + "/versions/1.0.0/candidate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("CANDIDATE"));
        mvc().perform(post("/api/models/" + model + "/versions/1.0.0/production"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("PRODUCTION"));
        mvc().perform(get("/api/models/" + model + "/production"))
                .andExpect(jsonPath("$.version").value("1.0.0"));

        // skipping the CANDIDATE stage is rejected
        publishViaHttp(model, "1.1.0", "new", "http-token-2");
        mvc().perform(post("/api/models/" + model + "/versions/1.1.0/production"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_STAGE_TRANSITION"));
    }

    @Test
    void binaryUploadIsByteExactOverHttp() throws Exception {
        String model = "binary-model";
        byte[] data = new byte[512];
        for (int i = 0; i < data.length; i++) {
            data[i] = (byte) i;
        }
        String uploadId = beginUpload(model, "1.0.0", null, sha256(data), data.length);
        mvc().perform(post("/api/models/" + model + "/uploads/" + uploadId + "/content")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data))
                .andExpect(status().isNoContent());
        mvc().perform(post("/api/models/" + model + "/uploads/" + uploadId + "/complete"))
                .andExpect(status().isOk());
        mvc().perform(get("/api/models/" + model + "/versions/1.0.0/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(data));
    }

    @Test
    void duplicateSubmissionReturnsConflictWithExistingVersion() throws Exception {
        String model = "dup-model";
        publishViaHttp(model, "1.0.0", "data", "dup-token");
        String body = mapper.writeValueAsString(java.util.Map.of(
                "model", model,
                "version", "1.0.0",
                "clientToken", "dup-token",
                "expectedChecksum", sha256("data".getBytes(StandardCharsets.UTF_8)),
                "contentType", "text/plain",
                "expectedSize", 4));
        mvc().perform(post("/api/models/" + model + "/uploads")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("DUPLICATE_SUBMISSION"))
                .andExpect(jsonPath("$.existingVersion").value("1.0.0"));
    }

    @Test
    void checksumMismatchIsRejected() throws Exception {
        String model = "bad-model";
        byte[] data = "real".getBytes(StandardCharsets.UTF_8);
        String uploadId = beginUpload(model, "1.0.0", null, "00".repeat(32), data.length);
        mvc().perform(post("/api/models/" + model + "/uploads/" + uploadId + "/content")
                        .contentType(MediaType.APPLICATION_OCTET_STREAM).content(data))
                .andExpect(status().isNoContent());
        mvc().perform(post("/api/models/" + model + "/uploads/" + uploadId + "/complete"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CHECKSUM_MISMATCH"));
    }

    @Test
    void rollbackAuditAndDeleteRulesOverHttp() throws Exception {
        String model = "retire-model";
        publishViaHttp(model, "1.0.0", "v1", "r-1");
        publishViaHttp(model, "2.0.0", "v2-longer", "r-2");
        mvc().perform(post("/api/models/" + model + "/versions/1.0.0/candidate")).andExpect(status().isOk());
        mvc().perform(post("/api/models/" + model + "/versions/1.0.0/production")).andExpect(status().isOk());
        mvc().perform(post("/api/models/" + model + "/versions/2.0.0/candidate")).andExpect(status().isOk());
        mvc().perform(post("/api/models/" + model + "/versions/2.0.0/production")).andExpect(status().isOk());

        // rollback over HTTP restores an arbitrary historical version
        String rollbackBody = mapper.writeValueAsString(java.util.Map.of(
                "targetVersion", "1.0.0", "reason", "hotfix-rollback"));
        mvc().perform(post("/api/models/" + model + "/rollback")
                        .contentType(MediaType.APPLICATION_JSON).content(rollbackBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value("1.0.0"))
                .andExpect(jsonPath("$.state").value("PRODUCTION"));
        mvc().perform(get("/api/models/" + model + "/production"))
                .andExpect(jsonPath("$.version").value("1.0.0"));

        mvc().perform(get("/api/models/" + model + "/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.action=='ROLLBACK')].version",
                        org.hamcrest.Matchers.hasItem("1.0.0")));

        // a version that has served PRODUCTION cannot be deleted directly
        mvc().perform(delete("/api/models/" + model + "/versions/2.0.0"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("ILLEGAL_DELETE"));
    }
}
