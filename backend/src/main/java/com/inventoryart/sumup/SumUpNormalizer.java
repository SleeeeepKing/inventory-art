package com.inventoryart.sumup;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class SumUpNormalizer {
  private static final Map<String, String> HEADER_ALIASES = aliases();
  static final Set<String> TARGET_FIELDS = Set.copyOf(HEADER_ALIASES.values());
  private static final List<DateTimeFormatter> DATE_FORMATS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE,
          DateTimeFormatter.ofPattern("d/M/uuuu"),
          DateTimeFormatter.ofPattern("d-M-uuuu"),
          DateTimeFormatter.ofPattern("M/d/uuuu"),
          DateTimeFormatter.ofPattern("d.M.uuuu"));
  private static final List<DateTimeFormatter> DATE_TIME_FORMATS =
      List.of(
          DateTimeFormatter.ISO_LOCAL_DATE_TIME,
          DateTimeFormatter.ofPattern("d/M/uuuu H:mm[:ss]"),
          DateTimeFormatter.ofPattern("d-M-uuuu H:mm[:ss]"),
          DateTimeFormatter.ofPattern("M/d/uuuu h:mm[:ss] a"));
  private static final Pattern PAN_CANDIDATE =
      Pattern.compile("(?<!\\d)(?:\\d[ -]?){12,18}\\d(?!\\d)");

  private SumUpNormalizer() {}

  static String suggestedTarget(String sourceHeader) {
    return HEADER_ALIASES.get(normalizeHeader(sourceHeader));
  }

  static Map<String, Object> sanitize(Map<String, String> raw) {
    Map<String, Object> sanitized = new LinkedHashMap<>();
    raw.forEach(
        (header, sourceValue) -> {
          String key = normalizeHeader(header);
          if (key.contains("cvv")
              || key.contains("cvc")
              || key.contains("cryptogramme")
              || key.contains("security code")
              || key.contains("code securite")) return;
          String value = cleanValue(sourceValue);
          if (key.contains("card number") || key.contains("numero carte") || key.contains("pan")) {
            value = maskCard(value);
          }
          value = maskPanCandidates(value);
          sanitized.put(cleanValue(header), value);
        });
    return sanitized;
  }

  static Map<String, Object> normalize(
      Map<String, Object> sanitized, Map<String, String> mappings, ZoneId zone) {
    Map<String, String> mapped = new LinkedHashMap<>();
    sanitized.forEach(
        (source, value) -> {
          String target = mappings.get(source);
          if (target == null) target = suggestedTarget(source);
          if (target != null && value != null && !value.toString().isBlank())
            mapped.put(target, value.toString().trim());
        });

    Map<String, Object> result = new LinkedHashMap<>();
    mapped.forEach((target, value) -> result.put(target, normalizeField(target, value)));
    Instant occurredAt = parseDateTime(mapped.get("date"), mapped.get("time"), zone);
    if (occurredAt != null) result.put("occurredAt", occurredAt.toString());
    result.remove("date");
    result.remove("time");
    if (result.containsKey("currency")) {
      result.put("currency", result.get("currency").toString().toUpperCase(Locale.ROOT));
    }
    return result;
  }

  static List<String> validate(ImportType type, Map<String, Object> normalized) {
    List<String> errors = new ArrayList<>();
    switch (type) {
      case TRANSACTION_HISTORY -> {
        require(normalized, "amount", errors);
        require(normalized, "currency", errors);
        require(normalized, "occurredAt", errors);
      }
      case ORDER_HISTORY -> {
        requireOne(normalized, List.of("transactionId", "transactionCode", "description"), errors);
        requireOne(normalized, List.of("amount", "revenue", "grossRevenue"), errors);
      }
      case PRODUCT_SALES -> {
        requireOne(normalized, List.of("productName", "sku"), errors);
        require(normalized, "quantity", errors);
        requireOne(normalized, List.of("revenue", "grossRevenue", "netRevenue"), errors);
      }
      case ACCOUNTING_REPORT -> {
        requireOne(normalized, List.of("grossRevenue", "netRevenue", "amount"), errors);
        require(normalized, "currency", errors);
      }
      case UNKNOWN -> errors.add("IMPORT_TYPE_UNKNOWN");
    }
    return errors;
  }

  static ImportType detectType(Iterable<String> targets) {
    Set<String> targetSet = new java.util.HashSet<>();
    targets.forEach(targetSet::add);
    if (targetSet.contains("productName") && targetSet.contains("quantity")) {
      return targetSet.contains("transactionId") || targetSet.contains("transactionCode")
          ? ImportType.ORDER_HISTORY
          : ImportType.PRODUCT_SALES;
    }
    if (targetSet.contains("tax")
        && (targetSet.contains("grossRevenue") || targetSet.contains("netRevenue"))
        && !targetSet.contains("transactionId")) return ImportType.ACCOUNTING_REPORT;
    if (targetSet.contains("amount")
        && (targetSet.contains("transactionId") || targetSet.contains("status"))) {
      return ImportType.TRANSACTION_HISTORY;
    }
    return ImportType.UNKNOWN;
  }

  static String fingerprint(java.util.UUID tenantId, Map<String, Object> normalized) {
    String canonical =
        String.join(
            "|",
            tenantId.toString(),
            value(normalized, "occurredAt"),
            value(normalized, "amount"),
            value(normalized, "currency"),
            value(normalized, "type"),
            normalizeText(value(normalized, "description")),
            normalizeText(value(normalized, "merchant")));
    return sha256(canonical);
  }

  static String normalizeText(String value) {
    return normalizeHeader(value).replace(' ', '_');
  }

  static String normalizeHeader(String value) {
    if (value == null) return "";
    return Normalizer.normalize(value, Normalizer.Form.NFD)
        .replaceAll("\\p{M}+", "")
        .toLowerCase(Locale.ROOT)
        .replace('\u00a0', ' ')
        .replaceAll("[^a-z0-9]+", " ")
        .trim()
        .replaceAll("\\s+", " ");
  }

  private static Object normalizeField(String field, String value) {
    return switch (field) {
      case "amount",
          "feeAmount",
          "netAmount",
          "refundAmount",
          "unitPrice",
          "discount",
          "revenue",
          "grossRevenue",
          "netRevenue",
          "tax" ->
          parseDecimal(value);
      case "quantity" -> parseDecimal(value).intValueExact();
      case "status" -> canonicalStatus(value);
      case "type" -> canonicalType(value);
      case "paymentMethod" -> canonicalPaymentMethod(value);
      default -> cleanValue(value);
    };
  }

  private static BigDecimal parseDecimal(String raw) {
    String value =
        raw.trim().replace("\u00a0", "").replace(" ", "").replaceAll("[^0-9,.(\\)-]", "");
    boolean parentheses = value.startsWith("(") && value.endsWith(")");
    value = value.replace("(", "").replace(")", "");
    int comma = value.lastIndexOf(',');
    int dot = value.lastIndexOf('.');
    if (comma >= 0 && dot >= 0) {
      char decimal = comma > dot ? ',' : '.';
      value = value.replace(decimal == ',' ? "." : ",", "");
      if (decimal == ',') value = value.replace(',', '.');
    } else if (comma >= 0) {
      int digitsAfter = value.length() - comma - 1;
      value =
          digitsAfter == 3 && value.indexOf(',') == comma
              ? value.replace(",", "")
              : value.replace(',', '.');
    } else if (dot >= 0 && value.length() - dot - 1 == 3 && value.indexOf('.') == dot) {
      value = value.replace(".", "");
    }
    if (value.isBlank() || value.equals("-"))
      throw new IllegalArgumentException("Invalid decimal value");
    BigDecimal number = new BigDecimal(value);
    return parentheses ? number.negate() : number;
  }

  private static Instant parseDateTime(String date, String time, ZoneId zone) {
    if (date == null || date.isBlank()) return null;
    try {
      return Instant.parse(date.trim());
    } catch (DateTimeParseException ignored) {
    }
    String combined = date.trim() + (time == null || time.isBlank() ? "" : " " + time.trim());
    for (DateTimeFormatter format : DATE_TIME_FORMATS) {
      try {
        return LocalDateTime.parse(combined, format).atZone(zone).toInstant();
      } catch (DateTimeParseException ignored) {
      }
    }
    for (DateTimeFormatter format : DATE_FORMATS) {
      try {
        LocalDate localDate = LocalDate.parse(date.trim(), format);
        LocalTime localTime = LocalTime.MIDNIGHT;
        if (time != null && !time.isBlank()) {
          try {
            localTime = LocalTime.parse(time.trim(), DateTimeFormatter.ISO_LOCAL_TIME);
          } catch (DateTimeParseException ignored) {
          }
        }
        return localDate.atTime(localTime).atZone(zone).toInstant();
      } catch (DateTimeParseException ignored) {
      }
    }
    return null;
  }

  private static String maskCard(String source) {
    String digits = source == null ? "" : source.replaceAll("\\D", "");
    if (digits.length() < 4) return "****";
    return "****" + digits.substring(digits.length() - 4);
  }

  private static String canonicalStatus(String value) {
    String normalized = enumValue(value);
    return switch (normalized) {
      case "REUSSI", "REUSSIE", "SUCCES", "PAYE", "PAYEE" -> "SUCCESSFUL";
      case "REMBOURSE", "REMBOURSEE" -> "REFUNDED";
      case "PARTIELLEMENT_REMBOURSE", "PARTIELLEMENT_REMBOURSEE" -> "PARTIALLY_REFUNDED";
      case "EN_ATTENTE" -> "PENDING";
      case "ECHOUE", "ECHOUEE", "ECHEC" -> "FAILED";
      case "ANNULE", "ANNULEE" -> "CANCELLED";
      default -> normalized;
    };
  }

  private static String canonicalType(String value) {
    String normalized = enumValue(value);
    return switch (normalized) {
      case "PAIEMENT", "VENTE" -> "PAYMENT";
      case "REMBOURSEMENT" -> "REFUND";
      case "RETROFACTURATION" -> "CHARGEBACK";
      case "VERSEMENT" -> "PAYOUT";
      case "FRAIS" -> "FEE";
      default -> normalized;
    };
  }

  private static String canonicalPaymentMethod(String value) {
    String normalized = enumValue(value);
    return switch (normalized) {
      case "CARTE", "CARTE_BANCAIRE" -> "CARD";
      case "ESPECES", "LIQUIDE" -> "CASH";
      case "VIREMENT", "VIREMENT_BANCAIRE" -> "BANK_TRANSFER";
      default -> normalized;
    };
  }

  private static String enumValue(String value) {
    return normalizeHeader(value).replace(' ', '_').toUpperCase(Locale.ROOT);
  }

  private static String maskPanCandidates(String source) {
    Matcher matcher = PAN_CANDIDATE.matcher(source == null ? "" : source);
    StringBuilder result = new StringBuilder();
    while (matcher.find()) {
      String digits = matcher.group().replaceAll("\\D", "");
      if (luhn(digits))
        matcher.appendReplacement(
            result, Matcher.quoteReplacement("****" + digits.substring(digits.length() - 4)));
    }
    matcher.appendTail(result);
    return result.toString();
  }

  private static boolean luhn(String digits) {
    int sum = 0;
    boolean doubleDigit = false;
    for (int index = digits.length() - 1; index >= 0; index--) {
      int value = digits.charAt(index) - '0';
      if (doubleDigit && (value *= 2) > 9) value -= 9;
      sum += value;
      doubleDigit = !doubleDigit;
    }
    return sum > 0 && sum % 10 == 0;
  }

  private static String cleanValue(String source) {
    if (source == null) return "";
    String cleaned = source.replaceAll("[\\p{Cntrl}&&[^\\r\\n\\t]]", "").trim();
    return cleaned.substring(0, Math.min(cleaned.length(), 2000));
  }

  private static String value(Map<String, Object> values, String key) {
    Object value = values.get(key);
    return value == null ? "" : value.toString().trim();
  }

  private static void require(Map<String, Object> values, String key, List<String> errors) {
    if (!values.containsKey(key) || value(values, key).isBlank())
      errors.add("MISSING_" + key.toUpperCase(Locale.ROOT));
  }

  private static void requireOne(
      Map<String, Object> values, List<String> keys, List<String> errors) {
    if (keys.stream().noneMatch(key -> values.containsKey(key) && !value(values, key).isBlank())) {
      errors.add("MISSING_ONE_OF_" + String.join("_", keys).toUpperCase(Locale.ROOT));
    }
  }

  private static String sha256(String source) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  private static Map<String, String> aliases() {
    Map<String, String> map = new LinkedHashMap<>();
    aliases(
        map,
        "transactionId",
        "transaction id",
        "transaction identifier",
        "id transaction",
        "identifiant transaction");
    aliases(
        map,
        "transactionCode",
        "transaction code",
        "code transaction",
        "ticket",
        "order id",
        "numero commande");
    aliases(map, "date", "date", "transaction date", "date transaction", "jour");
    aliases(map, "time", "time", "heure", "transaction time");
    aliases(map, "status", "status", "statut", "etat");
    aliases(map, "type", "type", "transaction type", "type transaction");
    aliases(map, "description", "description", "details", "libelle");
    aliases(map, "amount", "amount", "montant", "transaction amount", "montant transaction");
    aliases(map, "currency", "currency", "devise", "monnaie");
    aliases(map, "feeAmount", "fee", "fees", "frais", "sumup fee", "frais sumup");
    aliases(map, "netAmount", "net amount", "montant net");
    aliases(
        map, "refundAmount", "refund", "refund amount", "remboursement", "montant remboursement");
    aliases(map, "paymentMethod", "payment method", "mode de paiement", "moyen de paiement");
    aliases(map, "cardType", "card type", "type de carte");
    aliases(map, "payoutDate", "payout date", "date de versement", "date virement");
    aliases(
        map, "payoutReference", "payout reference", "reference versement", "reference virement");
    aliases(map, "productName", "product", "product name", "produit", "nom du produit", "article");
    aliases(map, "sku", "reference", "reference produit", "sku", "product reference");
    aliases(map, "quantity", "quantity", "quantite", "qty", "nombre");
    aliases(map, "unitPrice", "unit price", "prix unitaire", "prix");
    aliases(map, "discount", "discount", "discounts", "reduction", "reductions", "remise");
    aliases(map, "revenue", "revenue", "chiffre affaires", "ca");
    aliases(map, "grossRevenue", "gross revenue", "ca ttc", "chiffre affaires ttc", "montant brut");
    aliases(map, "netRevenue", "net revenue", "ca ht", "chiffre affaires ht");
    aliases(map, "tax", "vat", "tax", "tva", "montant tva", "tax amount");
    aliases(map, "merchant", "merchant", "commercant", "merchant code");
    aliases(map, "employee", "employee", "employe");
    aliases(map, "location", "location", "lieu", "emplacement");
    return Map.copyOf(map);
  }

  private static void aliases(Map<String, String> map, String target, String... sources) {
    for (String source : sources) map.put(normalizeHeader(source), target);
  }
}
