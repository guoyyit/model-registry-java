package com.github.guoyyit.modelregistryjava.registry.api;

import com.github.guoyyit.modelregistryjava.registry.AuditEvent;
import com.github.guoyyit.modelregistryjava.registry.UploadMarker;
import com.github.guoyyit.modelregistryjava.registry.VersionMetadata;
import com.github.guoyyit.modelregistryjava.registry.api.dto.BeginUploadRequest;
import com.github.guoyyit.modelregistryjava.registry.api.dto.BeginUploadResponse;
import com.github.guoyyit.modelregistryjava.registry.api.dto.CompleteUploadResponse;
import com.github.guoyyit.modelregistryjava.registry.api.dto.RetireRequest;
import com.github.guoyyit.modelregistryjava.registry.api.dto.RollbackRequest;
import com.github.guoyyit.modelregistryjava.registry.RegistryService;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;

/**
 * HTTP surface of the registry.
 *
 * <p>Uploads are two-phase: {@code POST .../uploads} opens an upload and returns
 * its id; raw bytes are appended via {@code POST .../content}; {@code POST
 * .../complete} verifies the checksum and atomically publishes. Repeated
 * submissions carrying the same {@code clientToken} never produce a second
 * version (they return {@code 409} with the existing version in the body).
 */
@RestController
@RequestMapping("/api/models")
public class RegistryController {

    private final RegistryService registryService;

    public RegistryController(RegistryService registryService) {
        this.registryService = registryService;
    }

    @PostMapping("/{model}/uploads")
    @ResponseStatus(HttpStatus.CREATED)
    public BeginUploadResponse beginUpload(@PathVariable String model,
                                           @RequestBody BeginUploadRequest request) {
        String requestModel = request.model() != null ? request.model() : model;
        UploadMarker marker = registryService.beginUpload(
                requestModel,
                request.version(),
                request.clientToken(),
                request.expectedChecksum(),
                request.contentType(),
                request.expectedSize());
        return new BeginUploadResponse(marker.uploadId(), marker.model(),
                marker.version(), marker.state().name());
    }

    @PostMapping(value = "/{model}/uploads/{uploadId}/content")
    public ResponseEntity<Void> appendContent(@PathVariable String model,
                                              @PathVariable String uploadId,
                                              @RequestBody(required = false) byte[] chunk) {
        try (InputStream in = new ByteArrayInputStream(chunk == null ? new byte[0] : chunk)) {
            registryService.appendUpload(uploadId, in);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{model}/uploads/{uploadId}/complete")
    public CompleteUploadResponse completeUpload(@PathVariable String model,
                                                 @PathVariable String uploadId) {
        VersionMetadata version = registryService.completeUpload(uploadId);
        return new CompleteUploadResponse(false, version);
    }

    @GetMapping
    public List<String> listModels() {
        return registryService.listModels();
    }

    @GetMapping("/{model}/versions")
    public List<VersionMetadata> listVersions(@PathVariable String model) {
        return registryService.listVersions(model);
    }

    @GetMapping("/{model}/versions/{version}")
    public VersionMetadata getVersion(@PathVariable String model,
                                      @PathVariable String version) {
        return registryService.getVersion(model, version);
    }

    @GetMapping("/{model}/versions/{version}/content")
    public ResponseEntity<Resource> download(@PathVariable String model,
                                             @PathVariable String version) {
        VersionMetadata meta = registryService.getVersion(model, version);
        InputStream in = registryService.readContent(model, version);
        String contentType = meta.contentType() != null ? meta.contentType()
                : MediaType.APPLICATION_OCTET_STREAM_VALUE;
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, contentType)
                .header(HttpHeaders.CONTENT_LENGTH, Long.toString(meta.size()))
                .header("X-Model-Checksum", meta.checksumAlg() + ":" + meta.checksum())
                .body(new InputStreamResource(in));
    }

    @GetMapping("/{model}/production")
    public VersionMetadata getProduction(@PathVariable String model) {
        return registryService.getProduction(model);
    }

    @PostMapping("/{model}/versions/{version}/candidate")
    public VersionMetadata promoteCandidate(@PathVariable String model,
                                            @PathVariable String version) {
        return registryService.promoteToCandidate(model, version);
    }

    @PostMapping("/{model}/versions/{version}/production")
    public VersionMetadata promoteProduction(@PathVariable String model,
                                             @PathVariable String version) {
        return registryService.promoteToProduction(model, version);
    }

    @PostMapping("/{model}/rollback")
    public VersionMetadata rollback(@PathVariable String model,
                                    @RequestBody RollbackRequest request) {
        return registryService.rollback(model, request.targetVersion(), request.reason());
    }

    @PostMapping("/{model}/versions/{version}/retire")
    public VersionMetadata retire(@PathVariable String model,
                                  @PathVariable String version,
                                  @RequestBody(required = false) RetireRequest request) {
        String reason = request == null ? null : request.reason();
        return registryService.retire(model, version, reason);
    }

    @DeleteMapping("/{model}/versions/{version}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String model, @PathVariable String version) {
        registryService.deleteVersion(model, version);
    }

    @GetMapping("/{model}/audit")
    public List<AuditEvent> audit(@PathVariable String model) {
        return registryService.auditTrail(model);
    }

    @GetMapping("/audit")
    public List<AuditEvent> fullAudit() {
        return registryService.fullAuditTrail();
    }
}
