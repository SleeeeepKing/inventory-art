package com.inventoryart.storage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/files")
public class FileController {
  private final FileService files;

  public FileController(FileService files) {
    this.files = files;
  }

  @PostMapping("/presign")
  public FileDtos.PresignUploadResponse presign(
      @Valid @RequestBody FileDtos.PresignUploadRequest request) {
    return files.presignProductImage(request);
  }

  @PostMapping("/{fileId}/confirm")
  public FileDtos.ConfirmFileResponse confirm(@PathVariable UUID fileId) {
    return files.confirm(fileId);
  }

  @GetMapping("/{fileId}/preview")
  public ResponseEntity<InputStreamResource> preview(@PathVariable UUID fileId) throws IOException {
    FileService.PreviewContent preview = files.preview(fileId);
    return ResponseEntity.ok()
        .cacheControl(CacheControl.noStore().cachePrivate())
        .header(HttpHeaders.VARY, HttpHeaders.AUTHORIZATION)
        .contentType(MediaType.parseMediaType(preview.contentType()))
        .contentLength(preview.size())
        .body(new InputStreamResource(preview.input()));
  }

  @DeleteMapping("/{fileId}")
  public ResponseEntity<Void> delete(@PathVariable UUID fileId) {
    files.delete(fileId);
    return ResponseEntity.noContent().build();
  }

  @PutMapping("/local")
  public ResponseEntity<Void> localUpload(
      @RequestParam String objectKey,
      @RequestParam long expires,
      @RequestParam String signature,
      @RequestHeader(HttpHeaders.CONTENT_TYPE) String contentType,
      @RequestHeader("X-Content-Sha256") String checksum,
      HttpServletRequest request)
      throws IOException {
    files.receiveLocalUpload(
        objectKey,
        expires,
        signature,
        request.getInputStream(),
        request.getContentLengthLong(),
        contentType,
        checksum);
    return ResponseEntity.noContent().build();
  }
}
