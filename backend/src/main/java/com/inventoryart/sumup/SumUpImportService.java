package com.inventoryart.sumup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.inventoryart.audit.AuditService;
import com.inventoryart.common.PageResponse;
import com.inventoryart.config.AppProperties;
import com.inventoryart.exception.BusinessException;
import com.inventoryart.exception.NotFoundException;
import com.inventoryart.security.CurrentUserService;
import com.inventoryart.storage.StorageService;
import jakarta.persistence.EntityManager;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.security.DigestInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class SumUpImportService {
    private final ImportBatchRepository batches;
    private final ImportRowRepository rows;
    private final ImportColumnMappingRepository columnMappings;
    private final ExternalProductMappingRepository productMappings;
    private final StorageService storage;
    private final SumUpFileParser parser;
    private final CurrentUserService currentUser;
    private final ObjectProvider<SumUpImportCommitter> committer;
    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final EntityManager entityManager;
    private final AuditService audit;
    private final long maxFileSize;
    private final long legacySpreadsheetMaxFileSize;
    private final int batchSize;
    private final int maxRows;

    public SumUpImportService(ImportBatchRepository batches, ImportRowRepository rows,
                              ImportColumnMappingRepository columnMappings,
                              ExternalProductMappingRepository productMappings,
                              StorageService storage, SumUpFileParser parser, CurrentUserService currentUser,
                              ObjectProvider<SumUpImportCommitter> committer, JdbcTemplate jdbc,
                              ObjectMapper objectMapper, EntityManager entityManager, AuditService audit,
                              AppProperties properties) {
        this.batches = batches;
        this.rows = rows;
        this.columnMappings = columnMappings;
        this.productMappings = productMappings;
        this.storage = storage;
        this.parser = parser;
        this.currentUser = currentUser;
        this.committer = committer;
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.entityManager = entityManager;
        this.audit = audit;
        this.maxFileSize = properties.getImportConfig().getMaxFileSize();
        this.legacySpreadsheetMaxFileSize = properties.getImportConfig().getLegacySpreadsheetMaxFileSize();
        this.batchSize = Math.max(50, properties.getImportConfig().getBatchSize());
        this.maxRows = Math.max(1, properties.getImportConfig().getMaxRows());
    }

    @Transactional
    public SumUpDtos.BatchResponse upload(MultipartFile file, ImportType requestedType) {
        String filename = safeFilename(file.getOriginalFilename());
        validateUpload(file, filename);
        UUID tenantId = currentUser.tenantId();
        String checksum = checksum(file);
        batches.findByTenantIdAndSourceProviderAndFileChecksum(tenantId, "SUMUP", checksum).ifPresent(existing -> {
            throw new BusinessException("DUPLICATE_IMPORT_FILE",
                "This file has already been uploaded as batch " + existing.getId(), HttpStatus.CONFLICT);
        });
        UUID batchId = UUID.randomUUID();
        String extension = filename.substring(filename.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        String key = "tenants/%s/imports/%s/source%s".formatted(tenantId, batchId, extension);
        String contentType = normalizedContentType(file.getContentType(), extension);
        try (InputStream input = file.getInputStream()) {
            storage.put(key, input, file.getSize(), contentType, Map.of("sha256", checksum));
        } catch (IOException exception) {
            throw new BusinessException("IMPORT_UPLOAD_FAILED", "Unable to store import file", HttpStatus.BAD_GATEWAY);
        }
        try {
            ImportBatch batch = ImportBatch.uploaded(tenantId, requestedType, filename, key, checksum, file.getSize(),
                currentUser.userId());
            // Keep the object-path UUID equal to the persisted batch UUID.
            setBatchId(batch, batchId);
            ImportBatch saved = batches.save(batch);
            audit.record(tenantId, "SUMUP_IMPORT_UPLOAD", "IMPORT_BATCH", saved.getId(), "SUCCESS",
                Map.of("size", file.getSize(), "type", saved.getImportType().name()));
            return SumUpDtos.BatchResponse.from(saved);
        } catch (RuntimeException exception) {
            storage.delete(key);
            if (exception instanceof DataIntegrityViolationException) {
                throw new BusinessException("DUPLICATE_IMPORT_FILE", "This file has already been uploaded", HttpStatus.CONFLICT);
            }
            throw exception;
        }
    }

    /* Package-private reflection-free helper is implemented on the entity below. */
    private static void setBatchId(ImportBatch batch, UUID id) { batch.assignId(id); }

    @Transactional(readOnly = true)
    public PageResponse<SumUpDtos.BatchResponse> list(Pageable pageable) {
        Page<SumUpDtos.BatchResponse> page = batches.findAllByTenantId(currentUser.tenantId(), pageable)
            .map(SumUpDtos.BatchResponse::from);
        return PageResponse.of(page);
    }

    @Transactional(readOnly = true)
    public SumUpDtos.BatchResponse get(UUID batchId) { return SumUpDtos.BatchResponse.from(required(batchId)); }

    @Transactional
    public SumUpDtos.AnalyzeResponse analyze(UUID batchId) {
        ImportBatch batch = required(batchId);
        batch.beginAnalysis();
        rows.deleteAllByTenantIdAndImportBatchId(batch.getTenantId(), batchId);
        columnMappings.deleteAllByTenantIdAndImportBatchId(batch.getTenantId(), batchId);
        ZoneId zone = tenantZone(batch.getTenantId());
        ProductMatchIndex productIndex = productMatchIndex(batch.getTenantId());
        Map<String, String> discoveredMappings = new LinkedHashMap<>();
        List<ImportRow> pending = new ArrayList<>(batchSize);
        int[] valid = {0};
        int[] errors = {0};
        int[] seen = {0};
        SumUpFileParser.ParseMetadata metadata;
        try (InputStream input = storage.get(batch.getStoredObjectKey())) {
            metadata = parser.parse(input, batch.getOriginalFilename(), (rowNumber, source) -> {
                if (++seen[0] > maxRows) {
                    throw new BusinessException("IMPORT_ROW_LIMIT_EXCEEDED",
                        "Import file exceeds the configured row limit");
                }
                source.keySet().forEach(header -> {
                    String target = SumUpNormalizer.suggestedTarget(header);
                    if (target != null) discoveredMappings.putIfAbsent(header, target);
                });
                Map<String, Object> sanitized = SumUpNormalizer.sanitize(source);
                ImportType rowType = batch.getImportType() == ImportType.UNKNOWN
                    ? SumUpNormalizer.detectType(discoveredMappings.values()) : batch.getImportType();
                Map<String, Object> normalized;
                List<String> validation;
                try {
                    normalized = SumUpNormalizer.normalize(sanitized, discoveredMappings, zone);
                    validation = SumUpNormalizer.validate(rowType, normalized);
                } catch (RuntimeException exception) {
                    normalized = Map.of();
                    validation = List.of("INVALID_VALUE_FORMAT");
                }
                if (validation.isEmpty()) valid[0]++; else errors[0]++;
                ImportRow importRow = ImportRow.analyzed(batch.getTenantId(), batchId, rowNumber, rowType,
                    sanitized, normalized, validation);
                importRow.linkProduct(autoMatch(normalized, productIndex));
                pending.add(importRow);
                if (pending.size() >= batchSize) {
                    rows.saveAll(pending);
                    rows.flush();
                    pending.clear();
                    entityManager.clear();
                }
            });
        } catch (IOException exception) {
            batch.failed();
            throw new BusinessException("IMPORT_ANALYSIS_FAILED", "Unable to read import file");
        }
        if (!pending.isEmpty()) {
            rows.saveAll(pending);
            rows.flush();
            pending.clear();
            entityManager.clear();
        }
        metadata.headers().forEach(header -> {
            String target = discoveredMappings.get(header);
            if (target != null) columnMappings.save(ImportColumnMapping.of(batch.getTenantId(), batchId, header, target));
        });
        ImportType detected = batch.getImportType() == ImportType.UNKNOWN
            ? SumUpNormalizer.detectType(discoveredMappings.values()) : batch.getImportType();
        boolean mappingRequired = detected == ImportType.UNKNOWN;
        batch.analysisComplete(detected, metadata.encoding(), metadata.delimiter(), metadata.emittedRows(),
            valid[0], errors[0], mappingRequired);
        ImportBatch savedBatch = batches.save(batch);
        audit.record(batch.getTenantId(), "SUMUP_IMPORT_ANALYZE", "IMPORT_BATCH", batchId, "SUCCESS",
            Map.of("rows", metadata.emittedRows(), "errors", errors[0]));
        return new SumUpDtos.AnalyzeResponse(SumUpDtos.BatchResponse.from(savedBatch), metadata.headers(),
            Map.copyOf(discoveredMappings));
    }

    @Transactional
    public SumUpDtos.BatchResponse applyColumnMapping(UUID batchId, SumUpDtos.ColumnMappingRequest request) {
        ImportBatch batch = required(batchId);
        verifyVersion(batch, request.expectedAnalysisVersion());
        if (batch.getStatus() != ImportBatchStatus.READY_FOR_MAPPING
            && batch.getStatus() != ImportBatchStatus.READY_FOR_CONFIRMATION) {
            throw SumUpExceptions.invalidState(batch.getStatus(), "change column mappings");
        }
        validateMappings(request.mappings());
        columnMappings.deleteAllByTenantIdAndImportBatchId(batch.getTenantId(), batchId);
        request.mappings().forEach((source, target) ->
            columnMappings.save(ImportColumnMapping.of(batch.getTenantId(), batchId, source, target)));
        ZoneId zone = tenantZone(batch.getTenantId());
        ImportType type = batch.getImportType() == ImportType.UNKNOWN
            ? SumUpNormalizer.detectType(request.mappings().values()) : batch.getImportType();
        int valid = 0;
        int errors = 0;
        ProductMatchIndex productIndex = productMatchIndex(batch.getTenantId());
        int pageNumber = 0;
        while (true) {
            Page<ImportRow> page = rows.findAllByTenantIdAndImportBatchId(batch.getTenantId(), batchId,
                PageRequest.of(pageNumber, batchSize, Sort.by("rowNumber")));
            for (ImportRow row : page.getContent()) {
                Map<String, Object> normalized;
                List<String> validation;
                try {
                    normalized = SumUpNormalizer.normalize(row.getSanitizedRawData(), request.mappings(), zone);
                    validation = SumUpNormalizer.validate(type, normalized);
                } catch (RuntimeException exception) {
                    normalized = Map.of();
                    validation = List.of("INVALID_VALUE_FORMAT");
                }
                row.remap(type, normalized, validation);
                row.linkProduct(autoMatch(normalized, productIndex));
                if (validation.isEmpty()) valid++; else errors++;
            }
            rows.saveAll(page.getContent());
            rows.flush();
            boolean last = page.isLast();
            entityManager.clear();
            if (last) break;
            pageNumber++;
        }
        batch.mappingApplied(valid, errors);
        ImportBatch saved = batches.save(batch);
        audit.record(batch.getTenantId(), "SUMUP_COLUMN_MAPPING_UPDATE", "IMPORT_BATCH", batchId, "SUCCESS",
            Map.of("columns", request.mappings().size()));
        return SumUpDtos.BatchResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public SumUpDtos.PreviewResponse preview(UUID batchId) {
        ImportBatch batch = required(batchId);
        ensureAnalyzed(batch);
        List<SumUpDtos.RowResponse> previewRows = rows.findAllByTenantIdAndImportBatchId(
            batch.getTenantId(), batchId, PageRequest.of(0, 20, Sort.by("rowNumber"))).stream()
            .map(SumUpDtos.RowResponse::from).toList();
        Integer duplicates = jdbc.queryForObject("""
            select count(*) from import_rows r
            where r.tenant_id = ? and r.import_batch_id = ? and exists (
              select 1 from external_transactions e where e.tenant_id = r.tenant_id and e.provider = 'SUMUP'
                and ((r.external_transaction_id is not null and e.provider_transaction_id = r.external_transaction_id)
                  or (r.fingerprint is not null and e.fingerprint = r.fingerprint)))
            """, Integer.class, batch.getTenantId(), batchId);
        int duplicateCount = duplicates == null ? 0 : duplicates;
        Integer needsMapping = jdbc.queryForObject("""
            select count(*) from import_rows
            where tenant_id = ? and import_batch_id = ? and processing_status = 'VALID'
              and (normalized_data ? 'productName' or normalized_data ? 'sku') and linked_product_id is null
            """, Integer.class, batch.getTenantId(), batchId);
        ImportType type = batch.getImportType();
        return new SumUpDtos.PreviewResponse(SumUpDtos.BatchResponse.from(batch), previewRows,
            Math.max(0, batch.getValidRows() - duplicateCount), duplicateCount, batch.getErrorRows(),
            needsMapping == null ? 0 : needsMapping,
            type == ImportType.TRANSACTION_HISTORY || type == ImportType.ORDER_HISTORY,
            type == ImportType.ORDER_HISTORY || type == ImportType.PRODUCT_SALES,
            type == ImportType.TRANSACTION_HISTORY || type == ImportType.ACCOUNTING_REPORT);
    }

    @Transactional
    public SumUpDtos.BatchResponse saveProductMappings(UUID batchId, SumUpDtos.ProductMappingsRequest request) {
        ImportBatch batch = required(batchId);
        verifyVersion(batch, request.expectedAnalysisVersion());
        UUID userId = currentUser.userId();
        List<ProductSelection> selections = new ArrayList<>();
        for (SumUpDtos.ProductMappingItem item : request.mappings()) {
            if ((item.externalProductName() == null || item.externalProductName().isBlank())
                && (item.externalProductReference() == null || item.externalProductReference().isBlank())) {
                throw new BusinessException("INVALID_PRODUCT_MAPPING", "Product name or reference is required");
            }
            Integer exists = jdbc.queryForObject("select count(*) from products where tenant_id = ? and id = ?",
                Integer.class, batch.getTenantId(), item.productId());
            if (exists == null || exists == 0) throw new NotFoundException("Product");
            selections.add(new ProductSelection(normalized(item.externalProductReference()),
                normalized(item.externalProductName()), item.productId()));
            if (!item.remember()) continue;
            String key = SumUpNormalizer.normalizeText(item.externalProductName() == null
                || item.externalProductName().isBlank() ? item.externalProductReference() : item.externalProductName());
            ExternalProductMapping mapping = productMappings
                .findByTenantIdAndProviderAndNormalizedExternalName(batch.getTenantId(), "SUMUP", key)
                .orElseGet(() -> ExternalProductMapping.create(batch.getTenantId(), item.externalProductReference(),
                    item.externalProductName(), item.productId(), userId));
            mapping.update(item.externalProductReference(), item.externalProductName(), item.productId());
            productMappings.save(mapping);
        }
        productMappings.flush();
        int pageNumber = 0;
        while (true) {
            Page<ImportRow> page = rows.findAllByTenantIdAndImportBatchId(batch.getTenantId(), batchId,
                PageRequest.of(pageNumber, batchSize, Sort.by("rowNumber")));
            for (ImportRow row : page.getContent()) {
                String sku = normalized(row.getNormalizedData().get("sku"));
                String name = normalized(row.getNormalizedData().get("productName"));
                selections.stream().filter(selection -> selection.matches(sku, name)).findFirst()
                    .ifPresent(selection -> row.linkProduct(selection.productId()));
            }
            rows.saveAll(page.getContent());
            rows.flush();
            boolean last = page.isLast();
            entityManager.clear();
            if (last) break;
            pageNumber++;
        }
        ImportBatch saved = batches.save(batch);
        audit.record(batch.getTenantId(), "SUMUP_PRODUCT_MAPPING_UPDATE", "IMPORT_BATCH", batchId, "SUCCESS",
            Map.of("mappings", request.mappings().size()));
        return SumUpDtos.BatchResponse.from(saved);
    }

    @Transactional
    public SumUpDtos.ImportActionResponse confirm(UUID batchId, SumUpDtos.ConfirmRequest request) {
        ImportBatch batch = required(batchId);
        verifyVersion(batch, request.expectedAnalysisVersion());
        if (batch.getStatus() != ImportBatchStatus.READY_FOR_CONFIRMATION) {
            throw SumUpExceptions.invalidState(batch.getStatus(), "be confirmed");
        }
        SumUpImportCommitter integration = committer.getIfAvailable();
        if (integration == null) {
            throw new BusinessException("IMPORT_COMMITTER_NOT_AVAILABLE",
                "Order and inventory import integration is not installed", HttpStatus.NOT_IMPLEMENTED);
        }
        batch.markImporting();
        SumUpImportCommitter.Result result = integration.confirm(new SumUpImportCommitter.ConfirmCommand(
            batch.getTenantId(), currentUser.userId(), batchId, batch.getAnalysisVersion(),
            request.applyInventory(), request.allowUnallocatedOrders()));
        batch.markCompleted(result);
        audit.record(batch.getTenantId(), "SUMUP_IMPORT_CONFIRM", "IMPORT_BATCH", batchId, "SUCCESS",
            Map.of("importedRows", result.importedRows(), "orderCount", result.orderCount(),
                "inventoryMovements", result.inventoryMovementCount()));
        return actionResponse(batch);
    }

    @Transactional
    public SumUpDtos.ImportActionResponse reverse(UUID batchId) {
        ImportBatch batch = required(batchId);
        if (batch.getStatus() != ImportBatchStatus.COMPLETED
            && batch.getStatus() != ImportBatchStatus.COMPLETED_WITH_ERRORS) {
            throw SumUpExceptions.invalidState(batch.getStatus(), "be reversed");
        }
        SumUpImportCommitter integration = committer.getIfAvailable();
        if (integration == null) {
            throw new BusinessException("IMPORT_COMMITTER_NOT_AVAILABLE",
                "Order and inventory reverse integration is not installed", HttpStatus.NOT_IMPLEMENTED);
        }
        integration.reverse(new SumUpImportCommitter.ReverseCommand(batch.getTenantId(), currentUser.userId(), batchId));
        batch.markReversed(currentUser.userId());
        audit.record(batch.getTenantId(), "SUMUP_IMPORT_REVERSE", "IMPORT_BATCH", batchId, "SUCCESS", Map.of());
        return actionResponse(batch);
    }

    @Transactional(readOnly = true)
    public PageResponse<SumUpDtos.RowResponse> rowPage(UUID batchId, Pageable pageable) {
        ImportBatch batch = required(batchId);
        Page<SumUpDtos.RowResponse> page = rows.findAllByTenantIdAndImportBatchId(batch.getTenantId(), batchId, pageable)
            .map(SumUpDtos.RowResponse::from);
        return PageResponse.of(page);
    }

    @Transactional(readOnly = true)
    public void writeErrors(UUID batchId, Writer output) throws IOException {
        ImportBatch batch = required(batchId);
        List<ImportRow> errorRows = rows.findAllByTenantIdAndImportBatchIdAndProcessingStatusInOrderByRowNumber(
            batch.getTenantId(), batchId, List.of(ImportRowStatus.ERROR, ImportRowStatus.SKIPPED));
        try (CSVPrinter csv = new CSVPrinter(output, CSVFormat.DEFAULT.builder()
            .setHeader("rowNumber", "status", "errors", "sanitizedRawData").get())) {
            for (ImportRow row : errorRows) {
                csv.printRecord(row.getRowNumber(), row.getProcessingStatus().name(),
                    safeSpreadsheetCell(String.join("|", row.getValidationErrors())),
                    safeSpreadsheetCell(objectMapper.writeValueAsString(row.getSanitizedRawData())));
            }
        }
    }

    private ImportBatch required(UUID batchId) {
        return batches.findByIdAndTenantId(batchId, currentUser.tenantId())
            .orElseThrow(() -> new NotFoundException("Import batch"));
    }

    private ZoneId tenantZone(UUID tenantId) {
        String timezone = jdbc.queryForObject("select timezone from tenants where id = ?", String.class, tenantId);
        try { return ZoneId.of(timezone == null ? "UTC" : timezone); }
        catch (RuntimeException exception) { return ZoneId.of("UTC"); }
    }

    private static void ensureAnalyzed(ImportBatch batch) {
        if (batch.getStatus() == ImportBatchStatus.UPLOADED || batch.getStatus() == ImportBatchStatus.ANALYZING
            || batch.getStatus() == ImportBatchStatus.FAILED) throw SumUpExceptions.invalidState(batch.getStatus(), "be previewed");
    }

    private static void verifyVersion(ImportBatch batch, int expected) {
        if (batch.getAnalysisVersion() != expected) {
            throw new BusinessException("STALE_IMPORT_ANALYSIS", "Import analysis has changed; reload the preview",
                HttpStatus.CONFLICT);
        }
    }

    private static void validateMappings(Map<String, String> mappings) {
        Set<String> targets = new HashSet<>();
        mappings.forEach((source, target) -> {
            if (!SumUpNormalizer.TARGET_FIELDS.contains(target)) {
                throw new BusinessException("INVALID_IMPORT_TARGET_FIELD", "Unsupported target field: " + target);
            }
            if (!targets.add(target)) {
                throw new BusinessException("DUPLICATE_IMPORT_TARGET_FIELD", "A target field can only be mapped once: " + target);
            }
        });
    }

    private void validateUpload(MultipartFile file, String filename) {
        if (file.isEmpty() || file.getSize() <= 0) throw new BusinessException("EMPTY_IMPORT_FILE", "Import file is empty");
        if (file.getSize() > maxFileSize) throw new BusinessException("IMPORT_FILE_TOO_LARGE", "Import file exceeds configured limit");
        String lower = filename.toLowerCase(Locale.ROOT);
        boolean csv = lower.endsWith(".csv");
        boolean xls = lower.endsWith(".xls");
        boolean xlsx = lower.endsWith(".xlsx");
        if (!csv && !xls && !xlsx) {
            throw new BusinessException("UNSUPPORTED_IMPORT_FILE", "Only CSV, XLS and XLSX files are supported");
        }
        if (xls && file.getSize() > legacySpreadsheetMaxFileSize) {
            throw new BusinessException("LEGACY_SPREADSHEET_TOO_LARGE",
                "Legacy XLS files exceed the configured safe-memory limit; export as XLSX or CSV");
        }
        try (InputStream input = file.getInputStream()) {
            byte[] magic = input.readNBytes(8);
            if (csv && (containsNull(magic) || startsWith(magic, new byte[]{'M', 'Z'}))) {
                throw new BusinessException("INVALID_CSV_FILE", "CSV file contains binary content");
            }
            if (xls && !startsWith(magic, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0})) {
                throw new BusinessException("INVALID_XLS_FILE", "XLS signature is invalid");
            }
            if (xlsx && !startsWith(magic, new byte[]{'P', 'K'})) {
                throw new BusinessException("INVALID_XLSX_FILE", "XLSX signature is invalid");
            }
        } catch (IOException exception) {
            throw new BusinessException("IMPORT_UPLOAD_FAILED", "Unable to read import file");
        }
    }

    private static String checksum(MultipartFile file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = new DigestInputStream(file.getInputStream(), digest)) {
                input.transferTo(OutputStream.nullOutputStream());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException exception) {
            throw new BusinessException("IMPORT_UPLOAD_FAILED", "Unable to read import file");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String safeFilename(String source) {
        String filename = source == null ? "" : source.replace('\\', '/');
        filename = filename.substring(filename.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (filename.isBlank() || !filename.contains(".")) {
            throw new BusinessException("INVALID_IMPORT_FILENAME", "Import filename must include an extension");
        }
        return filename.substring(0, Math.min(filename.length(), 500));
    }

    private static String normalizedContentType(String supplied, String extension) {
        return switch (extension) {
            case ".csv" -> "text/csv";
            case ".xls" -> "application/vnd.ms-excel";
            case ".xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            default -> supplied == null ? "application/octet-stream" : supplied;
        };
    }

    private static boolean containsNull(byte[] data) {
        for (byte value : data) if (value == 0) return true;
        return false;
    }

    private static boolean startsWith(byte[] source, byte[] prefix) {
        if (source.length < prefix.length) return false;
        for (int index = 0; index < prefix.length; index++) if (source[index] != prefix[index]) return false;
        return true;
    }

    private static String safeSpreadsheetCell(String value) {
        if (value == null || value.isEmpty()) return value;
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' ? "'" + value : value;
    }

    private static SumUpDtos.ImportActionResponse actionResponse(ImportBatch batch) {
        return new SumUpDtos.ImportActionResponse(batch.getId(), batch.getStatus(), batch.getImportedRows(),
            batch.getUpdatedRows(), batch.getDuplicateRows(), batch.getErrorRows(), batch.getOrderCount(),
            batch.getInventoryMovementCount());
    }

    private ProductMatchIndex productMatchIndex(UUID tenantId) {
        Map<String, UUID> external = new LinkedHashMap<>();
        jdbc.query("""
            select m.normalized_external_name, m.external_product_reference, m.product_id
            from external_product_mappings m join products p
              on p.tenant_id=m.tenant_id and p.id=m.product_id
            where m.tenant_id=? and m.provider='SUMUP' and p.enabled=true
            """, rs -> {
                UUID productId = rs.getObject("product_id", UUID.class);
                putIfPresent(external, normalized(rs.getString("normalized_external_name")), productId);
                putIfPresent(external, normalized(rs.getString("external_product_reference")), productId);
            }, tenantId);
        Map<String, UUID> skus = new LinkedHashMap<>();
        Map<String, UUID> names = new LinkedHashMap<>();
        Set<String> ambiguousNames = new HashSet<>();
        jdbc.query("select id,sku,name from products where tenant_id=? and enabled=true", rs -> {
            UUID productId = rs.getObject("id", UUID.class);
            putIfPresent(skus, normalized(rs.getString("sku")), productId);
            String name = normalized(rs.getString("name"));
            if (name != null && names.putIfAbsent(name, productId) != null) ambiguousNames.add(name);
        }, tenantId);
        ambiguousNames.forEach(names::remove);
        return new ProductMatchIndex(Map.copyOf(external), Map.copyOf(skus), Map.copyOf(names));
    }

    private static UUID autoMatch(Map<String, Object> values, ProductMatchIndex index) {
        String sku = normalized(values.get("sku"));
        String name = normalized(values.get("productName"));
        UUID match = sku == null ? null : index.external().get(sku);
        if (match == null && name != null) match = index.external().get(name);
        if (match == null && sku != null) match = index.skus().get(sku);
        if (match == null && name != null) match = index.names().get(name);
        return match;
    }

    private static void putIfPresent(Map<String, UUID> target, String key, UUID productId) {
        if (key != null && !key.isBlank()) target.putIfAbsent(key, productId);
    }

    private static String normalized(Object value) {
        if (value == null || value.toString().isBlank()) return null;
        return SumUpNormalizer.normalizeText(value.toString());
    }

    private record ProductMatchIndex(Map<String, UUID> external, Map<String, UUID> skus,
                                     Map<String, UUID> names) {}

    private record ProductSelection(String reference, String name, UUID productId) {
        boolean matches(String rowReference, String rowName) {
            return (reference != null && reference.equals(rowReference)) || (name != null && name.equals(rowName));
        }
    }
}
