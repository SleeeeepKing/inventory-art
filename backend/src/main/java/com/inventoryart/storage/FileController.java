package com.inventoryart.storage;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.util.UUID;
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

  @GetMapping("/{fileId}/download-url")
  public FileDtos.DownloadUrlResponse downloadUrl(@PathVariable UUID fileId) {
    return files.downloadUrl(fileId);
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

  @GetMapping("/local")
  public ResponseEntity<InputStreamResource> localDownload(
      @RequestParam String objectKey, @RequestParam long expires, @RequestParam String signature)
      throws IOException {
    StoredFile file = files.localDownloadFile(objectKey, expires, signature);
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType(file.getContentType()))
        .contentLength(file.getSize())
        .body(new InputStreamResource(files.openLocalDownload(file)));
  }
}
