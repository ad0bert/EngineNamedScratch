package engine.audio;

/** Mixer groups with their own volume; every sound plays on one of them, and all of them go through {@link #MASTER}. */
public enum AudioBus {
  MASTER, MUSIC, EFFECTS, UI
}
