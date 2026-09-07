package io.github.vihuynh72.brownie.spike.docxbinding;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import javax.imageio.ImageIO;

/**
 * A generated placeholder logo, standing in for a club's real inline logo. This spike works with
 * synthetic fixtures only, so this avoids checking in an actual image file (and its licensing
 * question) just to prove that an inline picture survives the fill pass untouched.
 */
final class SyntheticLogo {

  static final int SIZE_PX = 48;

  private SyntheticLogo() {}

  static byte[] pngBytes() {
    BufferedImage image = new BufferedImage(SIZE_PX, SIZE_PX, BufferedImage.TYPE_INT_RGB);
    Graphics2D graphics = image.createGraphics();
    try {
      graphics.setColor(new Color(0xC7, 0x8A, 0x2E));
      graphics.fillRect(0, 0, SIZE_PX, SIZE_PX);
      graphics.setColor(Color.WHITE);
      graphics.fillOval(SIZE_PX / 4, SIZE_PX / 4, SIZE_PX / 2, SIZE_PX / 2);
    } finally {
      graphics.dispose();
    }

    try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      ImageIO.write(image, "png", out);
      return out.toByteArray();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
