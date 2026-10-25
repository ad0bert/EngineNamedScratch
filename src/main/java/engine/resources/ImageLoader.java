package engine.resources;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import engine.render.ImageData;

/** Decodes PNG/JPEG/GIF/BMP via ImageIO (headless) into bottom-up RGBA pixels; independent of any graphics API. */
public final class ImageLoader {
  private ImageLoader() {
  }

  public static ImageData load(InputStream in, String name) throws IOException {
    System.setProperty("java.awt.headless", "true");
    final BufferedImage img = ImageIO.read(in);
    if (img == null) {
      throw new IOException("Unsupported or corrupt image: " + name);
    }
    return ImageData.fromBufferedImage(img);
  }
}
