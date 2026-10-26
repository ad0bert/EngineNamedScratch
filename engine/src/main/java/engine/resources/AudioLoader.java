package engine.resources;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.UnsupportedAudioFileException;

import engine.audio.AudioData;

/**
 * Decodes sound files into 16-bit {@link AudioData}: WAV (and the other formats {@code javax.sound.sampled} reads, such
 * as AIFF and AU; 8, 16, 24 and 32 bit PCM are converted) and Ogg Vorbis (see {@link VorbisDecoder}). The format is
 * recognized from the content, not the file name. Needs no sound device.
 */
public final class AudioLoader {
  private AudioLoader() {
  }

  public static AudioData load(InputStream in, String name) throws IOException {
    return decode(in.readAllBytes(), name);
  }

  public static AudioData decode(byte[] bytes, String name) throws IOException {
    if (isOgg(bytes)) {
      try {
        return decodeVorbis(bytes);
      } catch (IllegalArgumentException e) {
        throw new IOException("Could not decode " + name + ": " + e.getMessage(), e);
      }
    }
    return decodeSampled(bytes, name);
  }

  /** True if the data starts like an Ogg file. */
  public static boolean isOgg(byte[] bytes) {
    return bytes.length >= 4 && bytes[0] == 'O' && bytes[1] == 'g' && bytes[2] == 'g' && bytes[3] == 'S';
  }

  /** Decodes a whole Ogg Vorbis file. */
  public static AudioData decodeVorbis(byte[] bytes) {
    try (var decoder = new VorbisDecoder(bytes)) {
      final float seconds = VorbisDecoder.duration(bytes, decoder.sampleRate());
      final int channels = decoder.channels();
      short[] out = new short[Float.isNaN(seconds) ? 1 << 16 : Math.max(channels, (int) (seconds
          * decoder.sampleRate() * channels) + channels)];
      final short[] chunk = new short[4096 * channels];
      int n = 0;
      for (int r = decoder.read(chunk); r >= 0; r = decoder.read(chunk)) {
        if (n + r > out.length) {
          out = Arrays.copyOf(out, Math.max(n + r, out.length * 2));
        }
        System.arraycopy(chunk, 0, out, n, r);
        n += r;
      }
      return new AudioData(decoder.sampleRate(), channels, Arrays.copyOf(out, n));
    }
  }

  private static AudioData decodeSampled(byte[] bytes, String name) throws IOException {
    try (AudioInputStream source = javax.sound.sampled.AudioSystem.getAudioInputStream(
        new ByteArrayInputStream(bytes))) {
      final var format = source.getFormat();
      final int channels = format.getChannels();
      if (channels < 1 || channels > 2) {
        throw new IOException(name + ": only mono and stereo sounds are supported, not " + channels + " channels");
      }
      final var target = new AudioFormat(AudioFormat.Encoding.PCM_SIGNED, format.getSampleRate(), 16, channels,
          2 * channels, format.getSampleRate(), false);
      final byte[] pcm;
      if (matches(format, target)) {
        pcm = source.readAllBytes();
      } else {
        try (AudioInputStream converted = javax.sound.sampled.AudioSystem.getAudioInputStream(target, source)) {
          pcm = converted.readAllBytes();
        } catch (IllegalArgumentException e) {
          throw new IOException(name + ": cannot convert " + format + " to 16-bit PCM", e);
        }
      }
      final short[] samples = new short[pcm.length / 2];
      ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples);
      return new AudioData(Math.round(format.getSampleRate()), channels, samples);
    } catch (UnsupportedAudioFileException e) {
      throw new IOException(name + ": unsupported sound format", e);
    }
  }

  private static boolean matches(AudioFormat f, AudioFormat target) {
    return f.getEncoding().equals(AudioFormat.Encoding.PCM_SIGNED) && f.getSampleSizeInBits() == 16
        && !f.isBigEndian() && f.getChannels() == target.getChannels();
  }

  /** Encodes sound as a 16-bit PCM WAV file (for tools, tests and saving generated sounds). */
  public static byte[] toWav(AudioData data) {
    final int bytes = data.samples().length * 2;
    final var out = new ByteArrayOutputStream(44 + bytes);
    final var header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
    header.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + bytes);
    header.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16);
    header.putShort((short) 1).putShort((short) data.channels()).putInt(data.sampleRate());
    header.putInt(data.sampleRate() * data.channels() * 2).putShort((short) (data.channels() * 2));
    header.putShort((short) 16);
    header.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(bytes);
    out.writeBytes(header.array());
    final var body = ByteBuffer.allocate(bytes).order(ByteOrder.LITTLE_ENDIAN);
    body.asShortBuffer().put(data.samples());
    out.writeBytes(body.array());
    return out.toByteArray();
  }
}
