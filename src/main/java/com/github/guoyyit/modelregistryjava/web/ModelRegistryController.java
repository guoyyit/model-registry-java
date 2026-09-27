package com.github.guoyyit.modelregistryjava.web;

import com.github.guoyyit.modelregistryjava.domain.AuditEvent;
import com.github.guoyyit.modelregistryjava.domain.ModelVersion;
import com.github.guoyyit.modelregistryjava.service.ModelRegistryService;
import com.github.guoyyit.modelregistryjava.service.RegisterResult;
import com.github.guoyyit.modelregistryjava.service.UploadSession;
import com.github.guoyyit.modelregistryjava.service.VersionContent;
import com.github.guoyyit.modelregistryjava.web.dto.AuditEventDto;
import com.github.guoyyit.modelregistryjava.web.dto.LifecycleRequest;
import com.github.guoyyit.modelregistryjava.web.dto.RegisterResponse;
import com.github.guoyyit.modelregistryjava.web.dto.UploadStartRequest;
import com.github.guoyyit.modelregistryjava.web.dto.VersionDto;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

@RestController
public class ModelRegistryController {

    private final ModelRegistryService service;

    public ModelRegistryController(ModelRegistryService service) {
        this.service = service;
    }

    private VersionDto dto(ModelVersion v) {
        boolean currentProduction = false;
        try {
            ModelVersion current = service.getCurrentProduction(v.getModel());
            currentProduction = current != null && current.getVersion() == v.getVersion();
        } catch (RuntimeException ignored) {
            // no production version (or model) yet
        }
        return VersionDto.of(v, currentProduction);
    }

    // ---- one-shot registration ----

    @PostMapping("/api/models/{model}/versions")
    public RegisterResponse registerOneShot(@PathVariable String model,
                                            @RequestParam(required = false) Long version,
                                            @RequestParam(required = false) String expectedChecksum,
                                            @RequestParam(required = false) String contentType,
                                            @RequestParam("content") MultipartFile content) throws IOException {
        long requested = version == null ? -1L : version;
        String ct = contentType != null ? contentType : content.getContentType();
        RegisterResult result = service.register(model, requested, expectedChecksum, ct,
                content.getInputStream());
        return new RegisterResponse(dto(result.version()), result.reused(), null);
    }

    // ---- staged upload ----

    @PostMapping("/api/uploads")
    public RegisterResponse startUpload(@RequestBody UploadStartRequest req) {
        UploadSession session = service.beginUpload(req.model(), req.version(),
                req.expectedChecksum(), req.contentType());
        return new RegisterResponse(null, false, session.getUploadId());
    }

    @PutMapping("/api/uploads/{uploadId}/content")
    public ResponseEntity<Void> putContent(@PathVariable String uploadId,
                                           @RequestParam("content") MultipartFile content)
            throws IOException {
        // Session lookup validates the upload id; bytes go straight to the staged temp file.
        UploadSession session = service.getUploadSession(uploadId);
        try (InputStream in = content.getInputStream()) {
            java.nio.file.Files.copy(in, session.getTempFile(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/api/uploads/{uploadId}/complete")
    public RegisterResponse complete(@PathVariable String uploadId) {
        RegisterResult result = service.completeUpload(uploadId);
        return new RegisterResponse(dto(result.version()), result.reused(), uploadId);
    }

    @DeleteMapping("/api/uploads/{uploadId}")
    public ResponseEntity<Void> abort(@PathVariable String uploadId,
                                      @RequestParam(required = false) String reason) {
        service.abortUpload(uploadId, reason);
        return ResponseEntity.noContent().build();
    }

    // ---- reads ----

    @GetMapping("/api/models/{model}/versions")
    public List<VersionDto> list(@PathVariable String model,
                                 @RequestParam(defaultValue = "false") boolean includeArchived) {
        return service.listVersions(model, includeArchived).stream().map(this::dto).toList();
    }

    @GetMapping("/api/models/{model}/versions/{version}")
    public VersionDto get(@PathVariable String model, @PathVariable long version) {
        return dto(service.getVersion(model, version));
    }

    @GetMapping("/api/models/{model}/versions/{version}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable String model,
                                                       @PathVariable long version) {
        VersionContent c = service.readContent(model, version);
        MediaType mediaType = c.version().getContentType() != null
                ? MediaType.parseMediaType(c.version().getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(c.version().getSize())
                .header(HttpHeaders.ETAG, "\"" + c.version().getChecksum() + "\"")
                .body(new InputStreamResource(c.stream()));
    }

    @GetMapping("/api/models/{model}/production")
    public VersionDto production(@PathVariable String model) {
        return dto(service.getCurrentProduction(model));
    }

    @GetMapping("/api/models/{model}/production/content")
    public ResponseEntity<InputStreamResource> productionContent(@PathVariable String model) {
        VersionContent c = service.readProductionContent(model);
        MediaType mediaType = c.version().getContentType() != null
                ? MediaType.parseMediaType(c.version().getContentType())
                : MediaType.APPLICATION_OCTET_STREAM;
        return ResponseEntity.ok()
                .contentType(mediaType)
                .contentLength(c.version().getSize())
                .header(HttpHeaders.ETAG, "\"" + c.version().getChecksum() + "\"")
                .body(new InputStreamResource(c.stream()));
    }

    @GetMapping("/api/models/{model}/audit")
    public List<AuditEventDto> audit(@PathVariable String model) {
        return service.getAuditTrail(model).stream().map(AuditEventDto::of).toList();
    }

    // ---- lifecycle ----

    @PostMapping("/api/models/{model}/versions/{version}/candidate")
    public VersionDto toCandidate(@PathVariable String model, @PathVariable long version) {
        return dto(service.promoteToCandidate(model, version));
    }

    @PostMapping("/api/models/{model}/versions/{version}/production")
    public VersionDto toProduction(@PathVariable String model, @PathVariable long version) {
        return dto(service.promoteToProduction(model, version));
    }

    @PostMapping("/api/models/{model}/rollback/{targetVersion}")
    public VersionDto rollback(@PathVariable String model, @PathVariable long targetVersion,
                               @RequestBody(required = false) LifecycleRequest body) {
        return dto(service.rollback(model, targetVersion, body == null ? null : body.reason()));
    }

    @PostMapping("/api/models/{model}/versions/{version}/archive")
    public VersionDto archive(@PathVariable String model, @PathVariable long version,
                              @RequestBody(required = false) LifecycleRequest body) {
        return dto(service.archive(model, version, body == null ? null : body.reason()));
    }

    @PostMapping("/api/models/{model}/versions/{version}/restore")
    public VersionDto restore(@PathVariable String model, @PathVariable long version) {
        return dto(service.restore(model, version));
    }
}
