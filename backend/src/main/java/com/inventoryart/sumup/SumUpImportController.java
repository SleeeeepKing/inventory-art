package com.inventoryart.sumup;

import com.inventoryart.common.PageResponse;
import jakarta.validation.Valid;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

@RestController
@RequestMapping("/api/v1/imports/sumup")
public class SumUpImportController {
  private final SumUpImportService imports;

  public SumUpImportController(SumUpImportService imports) {
    this.imports = imports;
  }

  @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ResponseEntity<SumUpDtos.BatchResponse> upload(
      @RequestPart("file") MultipartFile file,
      @RequestParam UUID eventId,
      @RequestParam(defaultValue = "UNKNOWN") ImportType importType) {
    return ResponseEntity.status(201).body(imports.upload(file, importType, eventId));
  }

  @GetMapping
  public PageResponse<SumUpDtos.BatchResponse> list(Pageable pageable) {
    return imports.list(pageable);
  }

  @GetMapping("/{batchId}")
  public SumUpDtos.BatchResponse get(@PathVariable UUID batchId) {
    return imports.get(batchId);
  }

  @PutMapping("/{batchId}/event")
  public SumUpDtos.BatchResponse event(
      @PathVariable UUID batchId, @Valid @RequestBody SumUpDtos.EventRequest request) {
    return imports.assignEvent(batchId, request);
  }

  @PostMapping("/{batchId}/analyze")
  public SumUpDtos.AnalyzeResponse analyze(@PathVariable UUID batchId) {
    return imports.analyze(batchId);
  }

  @PutMapping("/{batchId}/column-mapping")
  public SumUpDtos.BatchResponse mappings(
      @PathVariable UUID batchId, @Valid @RequestBody SumUpDtos.ColumnMappingRequest request) {
    return imports.applyColumnMapping(batchId, request);
  }

  @GetMapping("/{batchId}/preview")
  public SumUpDtos.PreviewResponse preview(@PathVariable UUID batchId) {
    return imports.preview(batchId);
  }

  @PutMapping("/{batchId}/product-mappings")
  public SumUpDtos.BatchResponse productMappings(
      @PathVariable UUID batchId, @Valid @RequestBody SumUpDtos.ProductMappingsRequest request) {
    return imports.saveProductMappings(batchId, request);
  }

  @PostMapping("/{batchId}/confirm")
  public SumUpDtos.ImportActionResponse confirm(
      @PathVariable UUID batchId, @Valid @RequestBody SumUpDtos.ConfirmRequest request) {
    return imports.confirm(batchId, request);
  }

  @GetMapping("/{batchId}/rows")
  public PageResponse<SumUpDtos.RowResponse> rows(@PathVariable UUID batchId, Pageable pageable) {
    return imports.rowPage(batchId, pageable);
  }

  @GetMapping("/{batchId}/errors/export")
  public ResponseEntity<StreamingResponseBody> errors(@PathVariable UUID batchId) {
    StreamingResponseBody body =
        output -> {
          OutputStreamWriter writer = new OutputStreamWriter(output, StandardCharsets.UTF_8);
          writer.write('\ufeff');
          imports.writeErrors(batchId, writer);
          writer.flush();
        };
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
        .header(
            HttpHeaders.CONTENT_DISPOSITION,
            "attachment; filename=\"sumup-errors-" + batchId + ".csv\"")
        .body(body);
  }

  @PostMapping("/{batchId}/reverse")
  public SumUpDtos.ImportActionResponse reverse(@PathVariable UUID batchId) {
    return imports.reverse(batchId);
  }
}
