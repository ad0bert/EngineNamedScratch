package engine.net;

/** Received data that is cut short or does not make sense: read past the end, a length out of range, a bad value. */
public class NetDataException extends RuntimeException {
  private static final long serialVersionUID = 1L;

  public NetDataException(String message) {
    super(message);
  }
}
