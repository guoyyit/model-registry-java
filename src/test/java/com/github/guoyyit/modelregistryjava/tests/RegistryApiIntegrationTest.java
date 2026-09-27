package com.github.guoyyit.modelregistryjava.tests;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistryApiIntegrationTest {

    private static final String MODEL = "iris-classifier";

    // Created before the Spring context starts, so startup recovery sees an empty store.
    private static final Path DATA_DIR;

    static {
        try {
            DATA_DIR = Files_createTempDir();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Path Files_createTempDir() throws Exception {
        Path dir = java.nio.file.Files.createTempDirectory("registry-it-");
        dir.toFile().deleteOnExit();
        return dir;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        registry.add("registry.home", () -> DATA_DIR.toString());
        registry.add("registry.recover-on-startup", () -> "true");
    }

    @Autowired
    private WebApplicationContext context;

    private MockMvc mvc;
    private final ObjectMapper json = new ObjectMapper();

    private static String sha256(String s) throws Exception {
        return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
    }

    @BeforeAll
    void setup() {
        mvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    private long register(String body, String checksum) throws Exception {
        MockMultipartFile content = new MockMultipartFile("content", "model.bin",
                "application/octet-stream", body.getBytes(StandardCharsets.UTF_8));
        MvcResult result = mvc.perform(multipart("/api/models/" + MODEL + "/versions")
                        .file(content)
                        .param("expectedChecksum", checksum))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode node = json.readTree(result.getResponse().getContentAsString());
        return node.path("version").path("version").asLong();
    }

    @Test
    void fullLifecycleOverHttp() throws Exception {
        String v1Body = "weights-v1";
        String v2Body = "weights-v2-longer";
        long v1 = register(v1Body, sha256(v1Body));
        long v2 = register(v2Body, sha256(v2Body));
        assertTrue(v1 > 0 && v2 > v1);

        mvc.perform(get("/api/models/" + MODEL + "/versions/" + v1 + "/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(v1Body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(header().string("ETag", "\"" + sha256(v1Body) + "\""));

        // Stage skipping rejected
        mvc.perform(post("/api/models/" + MODEL + "/versions/" + v1 + "/production"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/models/" + MODEL + "/versions/" + v1 + "/candidate"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/models/" + MODEL + "/versions/" + v1 + "/production"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/models/" + MODEL + "/production"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"stage\":\"PRODUCTION\"")));

        mvc.perform(post("/api/models/" + MODEL + "/versions/" + v2 + "/candidate"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/models/" + MODEL + "/versions/" + v2 + "/production"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/models/" + MODEL + "/versions/" + v1))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"stage\":\"CANDIDATE\"")));

        mvc.perform(post("/api/models/" + MODEL + "/rollback/" + v1)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"http-rollback\"}"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"stage\":\"PRODUCTION\"")));
        mvc.perform(get("/api/models/" + MODEL + "/production/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(v1Body.getBytes(StandardCharsets.UTF_8)));

        mvc.perform(post("/api/models/" + MODEL + "/versions/" + v1 + "/archive"))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/models/" + MODEL + "/versions/" + v2 + "/archive"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/models/" + MODEL + "/versions/" + v2 + "/content"))
                .andExpect(status().isGone());
        mvc.perform(get("/api/models/" + MODEL + "/versions/" + v2))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("\"stage\":\"ARCHIVED\"")));
        mvc.perform(get("/api/models/" + MODEL + "/audit"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("http-rollback")));

        mvc.perform(get("/api/models/" + MODEL + "/production/content"))
                .andExpect(status().isOk())
                .andExpect(content().bytes(v1Body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void badChecksumRejectedWith422() throws Exception {
        MockMultipartFile content = new MockMultipartFile("content", "m.bin",
                "application/octet-stream", "actual".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/models/" + MODEL + "/versions")
                        .file(content)
                        .param("expectedChecksum", "0".repeat(64)))
                .andExpect(status().isUnprocessableEntity());
    }
}
