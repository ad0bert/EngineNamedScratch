package engine.resources;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import engine.render.HdrImage;

/**
 * Reads Radiance RGBE images ({@code .hdr}, {@code .pic}): the usual format of HDR environment maps. Supports the
 * flat and both run-length encoded scanline formats and the standard orientation ({@code -Y h +X w}, rows from the
 * top; {@code +Y h +X w} is flipped). The XYZE colour format and the EXPOSURE header are not interpreted.
 */
public final class HdrLoader {
  private HdrLoader() {
  }

  public static HdrImage load(InputStream stream) throws IOException {
    final var in = stream instanceof BufferedInputStream ? stream : new BufferedInputStream(stream);
    final var magic = line(in);
    if (!magic.startsWith("#?")) {
      throw new IOException("not a Radiance HDR image");
    }
    for (var header = line(in); !header.isEmpty(); header = line(in)) {
      if (header.startsWith("FORMAT=") && !header.equals("FORMAT=32-bit_rle_rgbe")) {
        throw new IOException("unsupported HDR format: " + header);
      }
    }
    final var size = line(in).trim().split("\\s+");
    if (size.length != 4 || !size[2].equals("+X") || !(size[0].equals("-Y") || size[0].equals("+Y"))) {
      throw new IOException("unsupported HDR orientation: " + String.join(" ", size));
    }
    final int height = Integer.parseInt(size[1]);
    final int width = Integer.parseInt(size[3]);
    final boolean bottomUp = size[0].equals("+Y");
    final float[] rgb = new float[width * height * 3];
    final byte[] scan = new byte[width * 4];
    for (int y = 0; y < height; y++) {
      readScanline(in, scan, width);
      final int row = bottomUp ? height - 1 - y : y;
      for (int x = 0; x < width; x++) {
        final int e = scan[x * 4 + 3] & 0xff;
        final int o = (row * width + x) * 3;
        if (e == 0) {
          continue; // black
        }
        final float f = Math.scalb(1f, e - (128 + 8));
        rgb[o] = (scan[x * 4] & 0xff) * f;
        rgb[o + 1] = (scan[x * 4 + 1] & 0xff) * f;
        rgb[o + 2] = (scan[x * 4 + 2] & 0xff) * f;
      }
    }
    return new HdrImage(width, height, rgb);
  }

  /** One scanline as RGBE bytes: new-style RLE (each channel separately), or flat / old-style RLE pixels. */
  private static void readScanline(InputStream in, byte[] scan, int width) throws IOException {
    final int b0 = read(in);
    final int b1 = read(in);
    final int b2 = read(in);
    final int b3 = read(in);
    if (width >= 8 && width < 0x8000 && b0 == 2 && b1 == 2 && (b2 & 0x80) == 0) {
      if ((b2 << 8 | b3) != width) {
        throw new IOException("HDR scanline width mismatch");
      }
      for (int channel = 0; channel < 4; channel++) {
        for (int x = 0; x < width;) {
          int count = read(in);
          if (count > 128) { // a run of one value
            count -= 128;
            if (x + count > width) {
              throw new IOException("bad HDR run");
            }
            final byte value = (byte) read(in);
            for (int k = 0; k < count; k++) {
              scan[(x++) * 4 + channel] = value;
            }
          } else { // literal bytes
            if (count == 0 || x + count > width) {
              throw new IOException("bad HDR run");
            }
            for (int k = 0; k < count; k++) {
              scan[(x++) * 4 + channel] = (byte) read(in);
            }
          }
        }
      }
      return;
    }
    // flat pixels, where (1, 1, 1, n) repeats the previous pixel (old run-length encoding)
    int x = 0;
    int shift = 0;
    int r = b0;
    int g = b1;
    int b = b2;
    int e = b3;
    while (true) {
      if (r == 1 && g == 1 && b == 1 && x > 0) {
        final int count = e << shift;
        for (int k = 0; k < count && x < width; k++, x++) {
          System.arraycopy(scan, (x - 1) * 4, scan, x * 4, 4);
        }
        shift += 8;
      } else {
        scan[x * 4] = (byte) r;
        scan[x * 4 + 1] = (byte) g;
        scan[x * 4 + 2] = (byte) b;
        scan[x * 4 + 3] = (byte) e;
        x++;
        shift = 0;
      }
      if (x >= width) {
        return;
      }
      r = read(in);
      g = read(in);
      b = read(in);
      e = read(in);
    }
  }

  private static int read(InputStream in) throws IOException {
    final int b = in.read();
    if (b < 0) {
      throw new EOFException("HDR image ends early");
    }
    return b;
  }

  private static String line(InputStream in) throws IOException {
    final var out = new ByteArrayOutputStream();
    for (int b = in.read(); b != '\n'; b = in.read()) {
      if (b < 0) {
        throw new EOFException("HDR header ends early");
      }
      out.write(b);
    }
    return out.toString(StandardCharsets.US_ASCII);
  }
}
