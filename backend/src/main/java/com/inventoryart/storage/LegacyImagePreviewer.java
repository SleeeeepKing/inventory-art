package com.inventoryart.storage;

import com.inventoryart.exception.BusinessException;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.springframework.http.HttpStatus;

final class LegacyImagePreviewer {
  static final int MAX_PREVIEW_DIMENSION = 480;
  private static final int MAX_DECODED_DIMENSION = MAX_PREVIEW_DIMENSION * 2;
  private static final int MAX_SOURCE_DIMENSION = 32_768;
  private static final long MAX_SOURCE_PIXELS = 100_000_000L;

  private LegacyImagePreviewer() {}

  static byte[] jpeg(InputStream source) throws IOException {
    try (ImageInputStream imageInput = ImageIO.createImageInputStream(source)) {
      if (imageInput == null) throw invalid();
      Iterator<ImageReader> readers = ImageIO.getImageReaders(imageInput);
      if (!readers.hasNext()) throw invalid();
      ImageReader reader = readers.next();
      try {
        reader.setInput(imageInput, true, true);
        int width = reader.getWidth(0);
        int height = reader.getHeight(0);
        validateSourceDimensions(width, height);
        ImageReadParam read = reader.getDefaultReadParam();
        int sampling =
            Math.max(1, (int) Math.ceil(Math.max(width, height) / (double) MAX_DECODED_DIMENSION));
        read.setSourceSubsampling(sampling, sampling, 0, 0);
        BufferedImage decoded = reader.read(0, read);
        return encodeJpeg(scale(decoded));
      } finally {
        reader.dispose();
      }
    } catch (BusinessException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw invalid();
    }
  }

  private static void validateSourceDimensions(int width, int height) {
    if (width <= 0
        || height <= 0
        || width > MAX_SOURCE_DIMENSION
        || height > MAX_SOURCE_DIMENSION
        || (long) width * height > MAX_SOURCE_PIXELS) {
      throw new BusinessException(
          "IMAGE_DIMENSIONS_TOO_LARGE",
          "Stored image dimensions are too large to preview safely",
          HttpStatus.UNPROCESSABLE_ENTITY);
    }
  }

  private static BufferedImage scale(BufferedImage source) {
    double ratio =
        Math.min(
            1d,
            Math.min(
                MAX_PREVIEW_DIMENSION / (double) source.getWidth(),
                MAX_PREVIEW_DIMENSION / (double) source.getHeight()));
    int width = Math.max(1, (int) Math.round(source.getWidth() * ratio));
    int height = Math.max(1, (int) Math.round(source.getHeight() * ratio));
    BufferedImage target = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = target.createGraphics();
    try {
      graphics.setColor(Color.WHITE);
      graphics.fillRect(0, 0, width, height);
      graphics.setRenderingHint(
          RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
      graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
      graphics.drawImage(source, 0, 0, width, height, null);
    } finally {
      graphics.dispose();
    }
    source.flush();
    return target;
  }

  private static byte[] encodeJpeg(BufferedImage image) throws IOException {
    Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
    if (!writers.hasNext()) throw invalid();
    ImageWriter writer = writers.next();
    try (ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageOutputStream imageOutput = ImageIO.createImageOutputStream(output)) {
      writer.setOutput(imageOutput);
      ImageWriteParam write = writer.getDefaultWriteParam();
      if (write.canWriteCompressed()) {
        write.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        write.setCompressionQuality(0.65f);
      }
      writer.write(null, new IIOImage(image, null, null), write);
      imageOutput.flush();
      return output.toByteArray();
    } finally {
      writer.dispose();
      image.flush();
    }
  }

  private static BusinessException invalid() {
    return new BusinessException(
        "INVALID_STORED_IMAGE",
        "Stored image cannot be converted to a safe preview",
        HttpStatus.UNPROCESSABLE_ENTITY);
  }
}
