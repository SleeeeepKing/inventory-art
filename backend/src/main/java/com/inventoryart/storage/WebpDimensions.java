package com.inventoryart.storage;

import com.inventoryart.exception.BusinessException;
import java.io.IOException;
import java.io.InputStream;

final class WebpDimensions {
  record Size(int width, int height) {}

  private WebpDimensions() {}

  static Size read(InputStream input) throws IOException {
    byte[] header = input.readNBytes(30);
    if (header.length < 25 || !ascii(header, 0, "RIFF") || !ascii(header, 8, "WEBP")) {
      throw invalid();
    }
    if (ascii(header, 12, "VP8X") && header.length >= 30) {
      return size(littleEndian24(header, 24) + 1, littleEndian24(header, 27) + 1);
    }
    if (ascii(header, 12, "VP8 ")
        && header.length >= 30
        && unsigned(header[23]) == 0x9d
        && unsigned(header[24]) == 0x01
        && unsigned(header[25]) == 0x2a) {
      int width = (unsigned(header[26]) | (unsigned(header[27]) << 8)) & 0x3fff;
      int height = (unsigned(header[28]) | (unsigned(header[29]) << 8)) & 0x3fff;
      return size(width, height);
    }
    if (ascii(header, 12, "VP8L") && header.length >= 25 && unsigned(header[20]) == 0x2f) {
      int bits =
          unsigned(header[21])
              | (unsigned(header[22]) << 8)
              | (unsigned(header[23]) << 16)
              | (unsigned(header[24]) << 24);
      return size((bits & 0x3fff) + 1, ((bits >>> 14) & 0x3fff) + 1);
    }
    throw invalid();
  }

  private static Size size(int width, int height) {
    if (width <= 0 || height <= 0) throw invalid();
    return new Size(width, height);
  }

  private static boolean ascii(byte[] bytes, int offset, String expected) {
    if (bytes.length < offset + expected.length()) return false;
    for (int index = 0; index < expected.length(); index++) {
      if (unsigned(bytes[offset + index]) != expected.charAt(index)) return false;
    }
    return true;
  }

  private static int littleEndian24(byte[] bytes, int offset) {
    return unsigned(bytes[offset])
        | (unsigned(bytes[offset + 1]) << 8)
        | (unsigned(bytes[offset + 2]) << 16);
  }

  private static int unsigned(byte value) {
    return value & 0xff;
  }

  private static BusinessException invalid() {
    return new BusinessException("INVALID_IMAGE_PREVIEW", "Image preview is not a valid WebP");
  }
}
