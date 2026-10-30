package engine.serialization;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import engine.animation.AnimationClip;
import engine.animation.Channel;
import engine.animation.Easing;
import engine.animation.Track;
import lombok.extern.slf4j.Slf4j;

/**
 * Animation clips in scene files: name and channels, each with its node, transform property and keys. Channels with a
 * custom {@link Channel.Target} (code) cannot be written and are left out with a warning.
 */
@Slf4j
final class ClipSerializer {
  private ClipSerializer() {
  }

  static Map<String, Object> write(AnimationClip clip) {
    final Map<String, Object> out = new LinkedHashMap<>();
    out.put("name", clip.name());
    final List<Object> channels = new ArrayList<>();
    int skipped = 0;
    for (final var c : clip.channels()) {
      if (c.property() == null) {
        skipped++;
        continue;
      }
      final Map<String, Object> m = new LinkedHashMap<>();
      m.put("node", c.node());
      m.put("property", c.property().name());
      final var t = c.track();
      m.put("width", t.width());
      m.put("interpolation", t.interpolation().name());
      if (t.easing() != Easing.LINEAR) {
        m.put("easing", t.easing().name());
      }
      m.put("times", t.times());
      m.put("values", t.values());
      channels.add(m);
    }
    if (skipped > 0) {
      log.warn("Clip {}: {} channel(s) with a custom target are code and are not saved", clip.name(), skipped);
    }
    out.put("channels", channels);
    return out;
  }

  static AnimationClip read(Map<String, Object> data) {
    final var clip = new AnimationClip((String) data.getOrDefault("name", "clip"));
    for (final Object o : Json.asList(data.getOrDefault("channels", List.of()))) {
      final var m = Json.asObject(o);
      final var property = Channel.Property.valueOf((String) m.get("property"));
      final var easing = m.containsKey("easing") ? Easing.valueOf((String) m.get("easing")) : Easing.LINEAR;
      final var track = new Track(Json.asFloats(m.get("times")), Json.asFloats(m.get("values")),
          Json.asInt(m.getOrDefault("width", (double) property.width)),
          Track.Interpolation.valueOf((String) m.getOrDefault("interpolation", "LINEAR")), easing);
      clip.add(Channel.transform((String) m.getOrDefault("node", ""), property, track));
    }
    return clip;
  }
}
