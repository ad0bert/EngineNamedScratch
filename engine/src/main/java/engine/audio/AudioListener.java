package engine.audio;

import engine.ecs.Component;

/**
 * Marks the entity sounds are heard from, usually the one with the camera. Its world position and orientation place
 * the "ears", its velocity (from a {@code Rigidbody}, or from its motion) drives the Doppler effect. Without a listener
 * the active camera is used; with several, the first enabled one.
 */
public class AudioListener extends Component {
}
