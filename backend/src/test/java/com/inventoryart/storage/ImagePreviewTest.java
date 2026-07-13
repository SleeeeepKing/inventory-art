package com.inventoryart.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.inventoryart.exception.BusinessException;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class ImagePreviewTest {
  @Test
  void readsWebpExtendedCanvasDimensions() throws Exception {
    WebpDimensions.Size size = WebpDimensions.read(new ByteArrayInputStream(webpHeader(480, 320)));

    assertThat(size.width()).isEqualTo(480);
    assertThat(size.height()).isEqualTo(320);
  }

  @Test
  void rejectsNonWebpPreviewData() {
    assertThatThrownBy(
            () ->
                WebpDimensions.read(
                    new ByteArrayInputStream("not-a-webp".getBytes(StandardCharsets.UTF_8))))
        .isInstanceOf(BusinessException.class);
  }

  @Test
  void boundsLegacyPreviewDimensions() throws Exception {
    BufferedImage source = new BufferedImage(1200, 600, BufferedImage.TYPE_INT_RGB);
    ByteArrayOutputStream original = new ByteArrayOutputStream();
    ImageIO.write(source, "png", original);

    byte[] encoded = LegacyImagePreviewer.jpeg(new ByteArrayInputStream(original.toByteArray()));
    BufferedImage preview = ImageIO.read(new ByteArrayInputStream(encoded));

    assertThat(preview.getWidth()).isEqualTo(480);
    assertThat(preview.getHeight()).isEqualTo(240);
  }

  private byte[] webpHeader(int width, int height) {
    byte[] bytes = new byte[30];
    ascii(bytes, 0, "RIFF");
    littleEndian(bytes, 4, 22, 4);
    ascii(bytes, 8, "WEBP");
    ascii(bytes, 12, "VP8X");
    littleEndian(bytes, 16, 10, 4);
    littleEndian(bytes, 24, width - 1, 3);
    littleEndian(bytes, 27, height - 1, 3);
    return bytes;
  }

  private void ascii(byte[] target, int offset, String value) {
    byte[] source = value.getBytes(StandardCharsets.US_ASCII);
    System.arraycopy(source, 0, target, offset, source.length);
  }

  private void littleEndian(byte[] target, int offset, int value, int length) {
    for (int index = 0; index < length; index++) {
      target[offset + index] = (byte) (value >>> (index * 8));
    }
  }
}
