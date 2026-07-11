package com.inventoryart.sumup;

import com.inventoryart.exception.BusinessException;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.openxml4j.exceptions.OpenXML4JException;
import org.apache.poi.openxml4j.opc.OPCPackage;
import org.apache.poi.openxml4j.opc.PackageAccess;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.util.XMLHelper;
import org.apache.poi.xssf.eventusermodel.ReadOnlySharedStringsTable;
import org.apache.poi.xssf.eventusermodel.XSSFReader;
import org.apache.poi.xssf.eventusermodel.XSSFSheetXMLHandler;
import org.apache.poi.xssf.model.StylesTable;
import org.springframework.stereotype.Component;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import javax.xml.parsers.ParserConfigurationException;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
final class SumUpFileParser {
    record ParseMetadata(String encoding, String delimiter, List<String> headers, int emittedRows) {}

    @FunctionalInterface
    interface RowConsumer { void accept(int rowNumber, Map<String, String> values); }

    ParseMetadata parse(InputStream source, String filename, RowConsumer consumer) throws IOException {
        String lower = filename.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".csv")) return parseCsv(source, consumer);
        if (lower.endsWith(".xls") || lower.endsWith(".xlsx")) return parseWorkbook(source, lower, consumer);
        throw new BusinessException("UNSUPPORTED_IMPORT_FILE", "Only CSV, XLS and XLSX files are supported");
    }

    private ParseMetadata parseCsv(InputStream source, RowConsumer consumer) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(source, 64 * 1024);
        buffered.mark(64 * 1024 + 4);
        byte[] sample = buffered.readNBytes(64 * 1024);
        buffered.reset();
        boolean bom = sample.length >= 3 && (sample[0] & 0xff) == 0xef && (sample[1] & 0xff) == 0xbb
            && (sample[2] & 0xff) == 0xbf;
        Charset charset = bom || validUtf8(sample) ? StandardCharsets.UTF_8 : Charset.forName("windows-1252");
        String sampleText = new String(sample, bom ? 3 : 0, sample.length - (bom ? 3 : 0), charset);
        char delimiter = detectDelimiter(sampleText);
        if (bom) buffered.skipNBytes(3);

        CSVFormat format = CSVFormat.DEFAULT.builder().setDelimiter(delimiter)
            .setIgnoreEmptyLines(true).setTrim(false).get();
        try (Reader reader = new InputStreamReader(buffered, charset); CSVParser parser = format.parse(reader)) {
            java.util.Iterator<CSVRecord> iterator = parser.iterator();
            List<CSVRecord> initial = new ArrayList<>();
            while (iterator.hasNext() && initial.size() < 25) initial.add(iterator.next());
            int headerIndex = chooseHeader(initial.stream().map(CSVRecord::toList).toList());
            if (headerIndex < 0) throw new BusinessException("MISSING_IMPORT_HEADER", "Import file has no header row");
            List<String> headers = validateHeaders(initial.get(headerIndex).toList());
            int count = 0;
            for (int index = headerIndex + 1; index < initial.size(); index++) {
                CSVRecord record = initial.get(index);
                Map<String, String> values = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++) {
                    values.put(headers.get(column), column < record.size() ? record.get(column) : "");
                }
                if (empty(values) || totalRow(values)) continue;
                count++;
                consumer.accept(Math.toIntExact(record.getRecordNumber()), values);
            }
            while (iterator.hasNext()) {
                CSVRecord record = iterator.next();
                Map<String, String> values = new LinkedHashMap<>();
                for (int column = 0; column < headers.size(); column++) {
                    values.put(headers.get(column), column < record.size() ? record.get(column) : "");
                }
                if (empty(values) || totalRow(values)) continue;
                count++;
                consumer.accept(Math.toIntExact(record.getRecordNumber()), values);
            }
            return new ParseMetadata(bom ? "UTF-8-BOM" : charset.name(), delimiterName(delimiter), headers, count);
        }
    }

    private ParseMetadata parseWorkbook(InputStream source, String filename, RowConsumer consumer) throws IOException {
        if (filename.endsWith(".xlsx")) return parseXlsx(source, consumer);
        try (Workbook workbook = WorkbookFactory.create(source)) {
            if (workbook.getNumberOfSheets() == 0) throw new BusinessException("EMPTY_IMPORT_FILE", "Workbook has no sheets");
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter(Locale.ROOT, false);
            List<List<String>> initial = new ArrayList<>();
            List<Integer> initialRowNumbers = new ArrayList<>();
            for (Row row : sheet) {
                List<String> values = cells(row, formatter);
                if (values.stream().allMatch(String::isBlank)) continue;
                initial.add(values);
                initialRowNumbers.add(row.getRowNum());
                if (initial.size() == 25) break;
            }
            int selected = chooseHeader(initial);
            if (selected < 0) throw new BusinessException("MISSING_IMPORT_HEADER", "Workbook has no header row");
            List<String> headers = validateHeaders(initial.get(selected));
            int headerRow = initialRowNumbers.get(selected);
            int count = 0;
            for (Row row : sheet) {
                if (row.getRowNum() <= headerRow) continue;
                List<String> cells = cells(row, formatter);
                if (cells.stream().allMatch(String::isBlank)) continue;
                Map<String, String> values = new LinkedHashMap<>();
                for (int index = 0; index < headers.size(); index++) {
                    values.put(headers.get(index), index < cells.size() ? cells.get(index) : "");
                }
                if (empty(values) || totalRow(values)) continue;
                count++;
                consumer.accept(row.getRowNum() + 1, values);
            }
            return new ParseMetadata("BIFF8", null, headers, count);
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new BusinessException("INVALID_SPREADSHEET", "Spreadsheet cannot be read safely");
        }
    }

    /**
     * XLSX is copied to bounded temporary disk and parsed with POI's SAX event
     * API. Workbook rows and cells are never materialized as an XSSFWorkbook.
     */
    private ParseMetadata parseXlsx(InputStream source, RowConsumer consumer) throws IOException {
        Path temporary = Files.createTempFile("sumup-import-", ".xlsx");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try (OPCPackage pkg = OPCPackage.open(temporary.toFile(), PackageAccess.READ)) {
                XSSFReader reader = new XSSFReader(pkg, true);
                java.util.Iterator<InputStream> sheets = reader.getSheetsData();
                if (!sheets.hasNext()) throw new BusinessException("EMPTY_IMPORT_FILE", "Workbook has no sheets");
                ReadOnlySharedStringsTable strings = new ReadOnlySharedStringsTable(pkg);
                StylesTable styles = reader.getStylesTable();
                StreamingSheetHandler rows = new StreamingSheetHandler(consumer);
                XMLReader xml = XMLHelper.newXMLReader();
                xml.setContentHandler(new XSSFSheetXMLHandler(styles, null, strings, rows,
                    new DataFormatter(Locale.ROOT, false), false));
                try (InputStream firstSheet = sheets.next()) {
                    xml.parse(new InputSource(firstSheet));
                }
                rows.finish();
                return new ParseMetadata("OOXML-SAX", null, rows.headers(), rows.emittedRows());
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (OpenXML4JException | SAXException | ParserConfigurationException | RuntimeException exception) {
            throw new BusinessException("INVALID_SPREADSHEET", "Spreadsheet cannot be read safely");
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static final class StreamingSheetHandler implements XSSFSheetXMLHandler.SheetContentsHandler {
        private static final int MAX_COLUMNS = 512;
        private final RowConsumer consumer;
        private final List<List<String>> initial = new ArrayList<>();
        private final List<Integer> initialRowNumbers = new ArrayList<>();
        private List<String> current;
        private List<String> headers;
        private int currentRow;
        private int headerRow = -1;
        private int emittedRows;

        private StreamingSheetHandler(RowConsumer consumer) { this.consumer = consumer; }

        @Override
        public void startRow(int rowNum) {
            currentRow = rowNum;
            current = new ArrayList<>();
        }

        @Override
        public void endRow(int rowNum) {
            if (current.stream().allMatch(String::isBlank)) return;
            if (headers == null && initial.size() < 25) {
                initial.add(List.copyOf(current));
                initialRowNumbers.add(rowNum);
                if (initial.size() == 25) selectHeaderAndEmitInitial();
                return;
            }
            if (headers == null) selectHeaderAndEmitInitial();
            if (rowNum > headerRow) emit(rowNum, current);
        }

        @Override
        public void cell(String cellReference, String formattedValue, org.apache.poi.xssf.usermodel.XSSFComment comment) {
            int column = cellReference == null ? current.size() : new CellReference(cellReference).getCol();
            if (column >= MAX_COLUMNS) {
                throw new BusinessException("IMPORT_COLUMN_LIMIT_EXCEEDED", "Spreadsheet has too many columns");
            }
            while (current.size() <= column) current.add("");
            String value = formattedValue == null ? "" : formattedValue.trim();
            current.set(column, value.substring(0, Math.min(value.length(), 10_000)));
        }

        void finish() {
            if (headers == null) selectHeaderAndEmitInitial();
        }

        List<String> headers() { return headers; }
        int emittedRows() { return emittedRows; }

        private void selectHeaderAndEmitInitial() {
            int selected = chooseHeader(initial);
            if (selected < 0) throw new BusinessException("MISSING_IMPORT_HEADER", "Workbook has no header row");
            headers = validateHeaders(initial.get(selected));
            headerRow = initialRowNumbers.get(selected);
            for (int index = selected + 1; index < initial.size(); index++) {
                emit(initialRowNumbers.get(index), initial.get(index));
            }
            initial.clear();
            initialRowNumbers.clear();
        }

        private void emit(int zeroBasedRow, List<String> cells) {
            Map<String, String> values = new LinkedHashMap<>();
            for (int index = 0; index < headers.size(); index++) {
                values.put(headers.get(index), index < cells.size() ? cells.get(index) : "");
            }
            if (empty(values) || totalRow(values)) return;
            emittedRows++;
            consumer.accept(zeroBasedRow + 1, values);
        }
    }

    private static List<String> cells(Row row, DataFormatter formatter) {
        int last = Math.max(row.getLastCellNum(), 0);
        List<String> values = new ArrayList<>(last);
        for (int index = 0; index < last; index++) {
            Cell cell = row.getCell(index, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
            values.add(cell == null ? "" : formatter.formatCellValue(cell).trim());
        }
        return values;
    }

    private static List<String> validateHeaders(List<String> source) {
        List<String> headers = new ArrayList<>(source.size());
        java.util.Set<String> normalized = new java.util.HashSet<>();
        for (int index = 0; index < source.size(); index++) {
            String header = source.get(index) == null ? "" : source.get(index).trim();
            if (header.isBlank()) header = "column_" + (index + 1);
            String key = SumUpNormalizer.normalizeHeader(header);
            if (!normalized.add(key)) header = header + "_" + (index + 1);
            headers.add(header);
        }
        if (headers.isEmpty()) throw new BusinessException("MISSING_IMPORT_HEADER", "Import file has no columns");
        return List.copyOf(headers);
    }

    private static int chooseHeader(List<? extends List<String>> rows) {
        int best = -1;
        int bestScore = -1;
        for (int index = 0; index < rows.size(); index++) {
            List<String> row = rows.get(index);
            int recognized = (int) row.stream().filter(value -> SumUpNormalizer.suggestedTarget(value) != null).count();
            int nonBlank = (int) row.stream().filter(value -> value != null && !value.isBlank()).count();
            int score = recognized * 100 + nonBlank;
            if (recognized >= 2 && score > bestScore) { best = index; bestScore = score; }
        }
        if (best >= 0) return best;
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).stream().filter(value -> value != null && !value.isBlank()).count() >= 2) return index;
        }
        return -1;
    }

    private static boolean empty(Map<String, String> values) {
        return values.values().stream().allMatch(value -> value == null || value.isBlank());
    }

    private static boolean totalRow(Map<String, String> values) {
        String first = values.values().stream().filter(value -> value != null && !value.isBlank()).findFirst().orElse("");
        String normalized = SumUpNormalizer.normalizeHeader(first);
        return normalized.equals("total") || normalized.equals("totals") || normalized.equals("total general")
            || normalized.equals("grand total") || normalized.equals("sous total");
    }

    private static boolean validUtf8(byte[] bytes) {
        try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes));
            return true;
        } catch (CharacterCodingException exception) {
            return false;
        }
    }

    private static char detectDelimiter(String sample) {
        String[] lines = sample.split("\\R", 12);
        char[] candidates = {',', ';', '\t'};
        int bestScore = -1;
        char best = ',';
        for (char candidate : candidates) {
            int expected = -1;
            int consistent = 0;
            for (String line : lines) {
                if (line.isBlank()) continue;
                int count = countOutsideQuotes(line, candidate);
                if (count == 0) continue;
                if (expected < 0) expected = count;
                if (count == expected) consistent++;
            }
            int score = expected < 0 ? -1 : consistent * 100 + expected;
            if (score > bestScore) { bestScore = score; best = candidate; }
        }
        if (bestScore < 0) throw new BusinessException("DELIMITER_NOT_DETECTED", "CSV delimiter could not be detected");
        return best;
    }

    private static int countOutsideQuotes(String line, char delimiter) {
        boolean quoted = false;
        int count = 0;
        for (int index = 0; index < line.length(); index++) {
            char current = line.charAt(index);
            if (current == '"') {
                if (quoted && index + 1 < line.length() && line.charAt(index + 1) == '"') index++;
                else quoted = !quoted;
            } else if (!quoted && current == delimiter) count++;
        }
        return count;
    }

    private static String delimiterName(char delimiter) {
        return delimiter == '\t' ? "TAB" : Character.toString(delimiter);
    }
}
