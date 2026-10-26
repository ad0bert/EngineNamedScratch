package engine.resources;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import engine.audio.AudioData;

class AudioLoaderTest {

  @Test
  void wavRoundTrip() throws IOException {
    final var data = new AudioData(11025, 2, new short[] { 0, 1000, -1000, 32767, -32768, 5 });
    final var decoded = AudioLoader.decode(AudioLoader.toWav(data), "test.wav");
    assertEquals(11025, decoded.sampleRate());
    assertEquals(2, decoded.channels());
    assertArrayEquals(data.samples(), decoded.samples());
  }

  @Test
  void eightBitWavIsConvertedTo16Bit() throws IOException {
    final byte[] pcm = { (byte) 128, (byte) 255, 0, (byte) 192 }; // unsigned 8 bit: silence, max, min, half
    final var b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length);
    b.put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1);
    b.putInt(8000).putInt(8000).putShort((short) 1).putShort((short) 8);
    b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
    final var decoded = AudioLoader.decode(b.array(), "8bit.wav");
    assertEquals(1, decoded.channels());
    assertEquals(4, decoded.frames());
    assertEquals(0, decoded.samples()[0]);
    assertTrue(decoded.samples()[1] > 32000);
    assertTrue(decoded.samples()[2] < -32000);
    assertEquals(0.5f, decoded.samples()[3] / 32768f, 0.01f);
  }

  @Test
  void garbageIsRejected() {
    assertThrows(IOException.class, () -> AudioLoader.decode(new byte[] { 1, 2, 3, 4, 5, 6, 7, 8 }, "x"));
    assertThrows(IOException.class, () -> AudioLoader.decode("OggS but not really".getBytes(), "y"));
  }

  private static byte[] tone() throws IOException {
    try (var in = AudioLoaderTest.class.getClassLoader().getResourceAsStream("audio/tone.ogg")) {
      return in.readAllBytes();
    }
  }

  @Test
  void vorbisDecodesTheTestTone() throws IOException {
    final byte[] ogg = tone(); // 0.5 s of 440 Hz, mono, 22050 Hz (generated with ffmpeg)
    assertTrue(AudioLoader.isOgg(ogg));
    final var data = AudioLoader.decode(ogg, "tone.ogg");
    assertEquals(22050, data.sampleRate());
    assertEquals(1, data.channels());
    assertEquals(0.5f, data.duration(), 0.02f); // plus the encoder's padding
    assertEquals(0.5f, VorbisDecoder.duration(ogg, 22050), 0.02f); // plus the encoder's padding
    int crossings = 0; // the pitch survived: count upward zero crossings
    float peak = 0f;
    final short[] s = data.samples();
    for (int i = 1; i < s.length; i++) {
      if (s[i - 1] < 0 && s[i] >= 0) {
        crossings++;
      }
      peak = Math.max(peak, Math.abs(s[i]) / 32768f);
    }
    assertEquals(440f, crossings / data.duration(), 5f);
    assertTrue(peak > 0.1f);
  }

  @Test
  void vorbisStreamsInSmallPieces() throws IOException {
    final byte[] ogg = tone();
    final var whole = AudioLoader.decodeVorbis(ogg);
    int total = 0;
    try (var stream = new VorbisDecoder(ogg)) {
      final short[] buffer = new short[333];
      for (int n = stream.read(buffer); n >= 0; n = stream.read(buffer)) {
        for (int i = 0; i < n; i++) {
          assertEquals(whole.samples()[total + i], buffer[i]);
        }
        total += n;
      }
    }
    assertEquals(whole.samples().length, total);
  }

  @Test
  void resourceManagerCachesSoundsAndStreamsMusic() {
    final var resources = new ResourceManager(new ResourceTest.FakeBackend());
    final var clip = resources.sound("audio/tone.ogg");
    assertEquals(clip, resources.sound("audio/tone.ogg"));
    assertEquals(0.5f, clip.duration(), 0.02f); // plus the encoder's padding
    final var music = resources.music("audio/tone.ogg");
    assertTrue(music.streaming());
    assertEquals(0.5f, music.duration(), 0.02f); // plus the encoder's padding
    assertNull(resources.sound("audio/missing.wav"));
  }
}
