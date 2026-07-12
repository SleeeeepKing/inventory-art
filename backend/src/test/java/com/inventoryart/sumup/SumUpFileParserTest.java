package com.inventoryart.sumup;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.Charset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class SumUpFileParserTest {
  private final SumUpFileParser parser = new SumUpFileParser();

  @Test
  void detectsFrenchSemicolonHeaderAfterPreambleAndWindows1252() throws Exception {
    String csv =
        "Rapport des transactions SumUp\r\nCréé le 11/07/2026\r\n"
            + "Identifiant transaction;Date;Heure;Montant;Devise;Statut;Mode de paiement\r\n"
            + "tx-1;10/07/2026;14:30:00;1.234,56;EUR;Réussi;Carte\r\n"
            + "Total;;;1.234,56;;;;\r\n";
    List<Map<String, String>> rows = new ArrayList<>();

    SumUpFileParser.ParseMetadata metadata =
        parser.parse(
            new ByteArrayInputStream(csv.getBytes(Charset.forName("windows-1252"))),
            "transactions.csv",
            (number, row) -> rows.add(row));

    assertThat(metadata.encoding()).isEqualTo("windows-1252");
    assertThat(metadata.delimiter()).isEqualTo(";");
    assertThat(metadata.emittedRows()).isEqualTo(1);
    assertThat(rows.getFirst()).containsEntry("Montant", "1.234,56");
    assertThat(SumUpNormalizer.suggestedTarget("Mode de paiement")).isEqualTo("paymentMethod");
  }

  @Test
  void parsesXlsxWithSaxAndSkipsTitleRows() throws Exception {
    byte[] workbook = workbook(new XSSFWorkbook());
    List<Map<String, String>> rows = new ArrayList<>();

    SumUpFileParser.ParseMetadata metadata =
        parser.parse(
            new ByteArrayInputStream(workbook), "orders.xlsx", (number, row) -> rows.add(row));

    assertThat(metadata.encoding()).isEqualTo("OOXML-SAX");
    assertThat(metadata.headers())
        .containsExactly("Transaction ID", "Product", "Reference", "Quantity", "Revenue");
    assertThat(rows)
        .singleElement()
        .satisfies(row -> assertThat(row).containsEntry("Reference", "ART-1"));
  }

  @Test
  void stillSupportsBoundedLegacyXlsParsing() throws Exception {
    byte[] workbook = workbook(new HSSFWorkbook());
    List<Map<String, String>> rows = new ArrayList<>();

    SumUpFileParser.ParseMetadata metadata =
        parser.parse(
            new ByteArrayInputStream(workbook), "orders.xls", (number, row) -> rows.add(row));

    assertThat(metadata.encoding()).isEqualTo("BIFF8");
    assertThat(rows).hasSize(1);
  }

  @Test
  void normalizesAmountsAndMasksPanInUnexpectedColumn() {
    Map<String, Object> sanitized =
        SumUpNormalizer.sanitize(
            Map.of(
                "Comment",
                "customer 4111 1111 1111 1111",
                "Montant",
                "1 234,56",
                "Devise",
                "eur",
                "Date",
                "10/07/2026"));
    Map<String, Object> normalized =
        SumUpNormalizer.normalize(sanitized, Map.of(), ZoneId.of("Europe/Paris"));

    assertThat(sanitized.get("Comment")).isEqualTo("customer ****1111");
    assertThat(normalized.get("amount").toString()).isEqualTo("1234.56");
    assertThat(normalized.get("currency")).isEqualTo("EUR");
    assertThat(
            SumUpNormalizer.fingerprint(
                UUID.fromString("00000000-0000-0000-0000-000000000001"), normalized))
        .hasSize(64);
  }

  @Test
  void canonicalizesFrenchTransactionEnums() {
    Map<String, Object> normalized =
        SumUpNormalizer.normalize(
            Map.of("Statut", "Réussi", "Type", "Paiement", "Mode de paiement", "Carte"),
            Map.of(),
            ZoneId.of("UTC"));
    assertThat(normalized)
        .containsEntry("status", "SUCCESSFUL")
        .containsEntry("type", "PAYMENT")
        .containsEntry("paymentMethod", "CARD");
  }

  private static byte[] workbook(Workbook workbook) throws Exception {
    try (workbook;
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      var sheet = workbook.createSheet("Export");
      sheet.createRow(0).createCell(0).setCellValue("SumUp sales report");
      var header = sheet.createRow(2);
      List<String> headers =
          List.of("Transaction ID", "Product", "Reference", "Quantity", "Revenue");
      for (int index = 0; index < headers.size(); index++)
        header.createCell(index).setCellValue(headers.get(index));
      var data = sheet.createRow(3);
      data.createCell(0).setCellValue("tx-1");
      data.createCell(1).setCellValue("Limited print");
      data.createCell(2).setCellValue("ART-1");
      data.createCell(3).setCellValue(2);
      data.createCell(4).setCellValue(50);
      workbook.write(output);
      return output.toByteArray();
    }
  }
}
