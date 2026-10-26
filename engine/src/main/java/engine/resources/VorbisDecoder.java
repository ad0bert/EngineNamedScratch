package engine.resources;

import com.jcraft.jogg.Packet;
import com.jcraft.jogg.Page;
import com.jcraft.jogg.StreamState;
import com.jcraft.jogg.SyncState;
import com.jcraft.jorbis.Block;
import com.jcraft.jorbis.Comment;
import com.jcraft.jorbis.DspState;
import com.jcraft.jorbis.Info;

import engine.audio.PcmStream;

/**
 * Decodes an Ogg Vorbis file held in memory, piece by piece (JOrbis, pure Java). Used for whole clips by
 * {@link AudioLoader} and as the stream behind streaming clips, so long music stays compressed in memory. Only the
 * first logical stream of a file is played (no chained files).
 */
public final class VorbisDecoder implements PcmStream {
  private static final int CHUNK = 4096;

  private final byte[] data;
  private int read;
  private final SyncState sync = new SyncState();
  private final StreamState stream = new StreamState();
  private final Page page = new Page();
  private final Packet packet = new Packet();
  private final Info info = new Info();
  private final Comment comment = new Comment();
  private final DspState dsp = new DspState();
  private final Block block = new Block(this.dsp);
  private final float[][][] pcm = new float[1][][];
  private final int[] index;
  private boolean endOfStream;
  // decoded samples not yet handed out (when the caller's buffer was full)
  private float[][] pending;
  private int pendingOffset;
  private int pendingFrames;

  /** @throws IllegalArgumentException if the data is not Ogg Vorbis */
  public VorbisDecoder(byte[] data) {
    this.data = data;
    this.sync.init();
    this.info.init();
    this.comment.init();
    if (!nextPage()) {
      throw new IllegalArgumentException("not an Ogg file");
    }
    this.stream.init(this.page.serialno());
    this.stream.pagein(this.page);
    if (this.stream.packetout(this.packet) != 1 || this.info.synthesis_headerin(this.comment, this.packet) < 0) {
      throw new IllegalArgumentException("not an Ogg Vorbis stream");
    }
    for (int headers = 1; headers < 3;) { // the comment and codebook headers may span pages
      final int r = this.stream.packetout(this.packet);
      if (r == 1) {
        if (this.info.synthesis_headerin(this.comment, this.packet) < 0) {
          throw new IllegalArgumentException("corrupt Vorbis header");
        }
        headers++;
      } else if (r == 0) {
        if (!nextPage()) {
          throw new IllegalArgumentException("truncated Vorbis header");
        }
        this.stream.pagein(this.page);
      } else {
        throw new IllegalArgumentException("corrupt Vorbis header");
      }
    }
    if (this.info.channels < 1 || this.info.channels > 2) {
      throw new IllegalArgumentException("only mono and stereo Vorbis files are supported");
    }
    this.dsp.synthesis_init(this.info);
    this.block.init(this.dsp);
    this.index = new int[this.info.channels];
  }

  @Override
  public int sampleRate() {
    return this.info.rate;
  }

  @Override
  public int channels() {
    return this.info.channels;
  }

  @Override
  public int read(short[] out) {
    final int channels = this.info.channels;
    final int maxFrames = out.length / channels;
    int frames = 0;
    while (frames < maxFrames) {
      if (this.pendingFrames > 0) {
        final int take = Math.min(this.pendingFrames, maxFrames - frames);
        for (int c = 0; c < channels; c++) {
          final float[] src = this.pending[c];
          final int start = this.index[c] + this.pendingOffset;
          for (int f = 0; f < take; f++) {
            float v = src[start + f];
            v = v > 1f ? 1f : v < -1f ? -1f : v;
            out[(frames + f) * channels + c] = (short) (v * 32767f);
          }
        }
        frames += take;
        this.pendingOffset += take;
        this.pendingFrames -= take;
        if (this.pendingFrames == 0) {
          this.dsp.synthesis_read(this.pendingOffset);
          this.pending = null;
        }
        continue;
      }
      final int available = this.dsp.synthesis_pcmout(this.pcm, this.index);
      if (available > 0) {
        this.pending = this.pcm[0];
        this.pendingOffset = 0;
        this.pendingFrames = available;
        continue;
      }
      if (!nextPacket()) {
        break;
      }
    }
    return frames == 0 && this.endOfStream ? -1 : frames * channels;
  }

  /** Feeds the next audio packet into the synthesizer; false at the end of the stream. */
  private boolean nextPacket() {
    while (true) {
      final int r = this.stream.packetout(this.packet);
      if (r == 1) {
        if (this.block.synthesis(this.packet) == 0) {
          this.dsp.synthesis_blockin(this.block);
        }
        return true;
      }
      if (r < 0) {
        continue; // a hole in the data: skip it
      }
      if (this.endOfStream || !nextPage()) {
        this.endOfStream = true;
        return false;
      }
      this.stream.pagein(this.page);
      if (this.page.eos() != 0) {
        this.endOfStream = true; // the packets of this last page are still decoded
      }
    }
  }

  /** Reads the next page from the data; false at its end. */
  private boolean nextPage() {
    while (true) {
      final int r = this.sync.pageout(this.page);
      if (r == 1) {
        return true;
      }
      if (this.read >= this.data.length) {
        return false;
      }
      final int n = Math.min(CHUNK, this.data.length - this.read);
      final int at = this.sync.buffer(n);
      System.arraycopy(this.data, this.read, this.sync.data, at, n);
      this.sync.wrote(n);
      this.read += n;
    }
  }

  /**
   * Length in seconds, read from the granule position (sample count) of the last page; NaN if it cannot be found.
   * Does not decode anything.
   */
  public static float duration(byte[] data, int sampleRate) {
    for (int i = data.length - 27; i >= 0; i--) {
      if (data[i] == 'O' && data[i + 1] == 'g' && data[i + 2] == 'g' && data[i + 3] == 'S') {
        long granule = 0;
        for (int b = 7; b >= 0; b--) {
          granule = granule << 8 | data[i + 6 + b] & 0xFF;
        }
        if (granule > 0) {
          return (float) granule / sampleRate;
        }
      }
    }
    return Float.NaN;
  }

  @Override
  public void close() {
    this.stream.clear();
    this.block.clear();
    this.dsp.clear();
    this.info.clear();
    this.sync.clear();
  }
}
