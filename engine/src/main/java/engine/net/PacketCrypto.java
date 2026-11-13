package engine.net;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.interfaces.XECPublicKey;
import java.security.spec.NamedParameterSpec;
import java.security.spec.XECPublicKeySpec;
import java.util.Arrays;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Encryption of a connection's packets with AES-256-GCM, and the key exchange that sets it up (X25519, keys derived
 * with HMAC-SHA256, optionally mixed with a password). Everything comes with the JDK.
 *
 * <p>
 * Each direction has its own key; the nonce is the direction and the packet's full 64-bit sequence number, which
 * never repeats on a connection, and the packet header (up to and including the sequence and acknowledgements) is
 * authenticated with the body. A packet that was changed, replayed under another number or made up fails to open and
 * is dropped.
 */
final class PacketCrypto {
  static final int TAG = 16;
  static final int PUBLIC_KEY = 32;
  static final int PROOF = 16;
  private static final int CLIENT_TO_SERVER = 1;
  private static final int SERVER_TO_CLIENT = 2;

  private final SecretKeySpec sendKey;
  private final SecretKeySpec receiveKey;
  private final int sendDirection;
  private final int receiveDirection;
  private final Cipher cipher;

  private PacketCrypto(byte[] send, byte[] receive, boolean server) {
    this.sendKey = new SecretKeySpec(send, "AES");
    this.receiveKey = new SecretKeySpec(receive, "AES");
    this.sendDirection = server ? SERVER_TO_CLIENT : CLIENT_TO_SERVER;
    this.receiveDirection = server ? CLIENT_TO_SERVER : SERVER_TO_CLIENT;
    try {
      this.cipher = Cipher.getInstance("AES/GCM/NoPadding");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("AES-GCM is not available", e);
    }
  }

  /** The session keys of one connection: client to server, server to client. */
  record Keys(byte[] clientToServer, byte[] serverToClient) {
    PacketCrypto forServer() {
      return new PacketCrypto(this.serverToClient, this.clientToServer, true);
    }

    PacketCrypto forClient() {
      return new PacketCrypto(this.clientToServer, this.serverToClient, false);
    }

    /** Proves to the client that the server derived the same keys (and so knew the password, if there is one). */
    byte[] confirmation(byte[] transcript) {
      return seal(this.serverToClient, 3, transcript);
    }
  }

  /** Encrypts the packet's bytes from {@code headerEnd} to its position, in place, and appends the tag. */
  void seal(ByteBuffer packet, int headerEnd, long sequence) {
    final byte[] array = packet.array();
    final byte[] plain = Arrays.copyOfRange(array, headerEnd, packet.position());
    try {
      this.cipher.init(Cipher.ENCRYPT_MODE, this.sendKey, nonce(this.sendDirection, sequence));
      this.cipher.updateAAD(array, 0, headerEnd);
      final int written = this.cipher.doFinal(plain, 0, plain.length, array, headerEnd);
      packet.position(headerEnd + written);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  /** The decrypted bytes after {@code headerEnd} up to the packet's limit; throws if the packet is not authentic. */
  ByteBuffer open(ByteBuffer packet, int headerEnd, long sequence) {
    final int length = packet.limit() - headerEnd;
    if (length < TAG || sequence < 0) {
      throw new NetDataException("too short to be encrypted");
    }
    try {
      this.cipher.init(Cipher.DECRYPT_MODE, this.receiveKey, nonce(this.receiveDirection, sequence));
      this.cipher.updateAAD(packet.array(), 0, headerEnd);
      return ByteBuffer.wrap(this.cipher.doFinal(packet.array(), headerEnd, length));
    } catch (AEADBadTagException e) {
      throw new NetDataException("not authentic");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("decryption failed", e);
    }
  }

  private static GCMParameterSpec nonce(int direction, long sequence) {
    return new GCMParameterSpec(TAG * 8, ByteBuffer.allocate(12).putInt(direction).putLong(sequence).array());
  }

  private static byte[] seal(byte[] key, int direction, byte[] aad) {
    try {
      final var c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), nonce(direction, 0));
      c.updateAAD(aad);
      return c.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("encryption failed", e);
    }
  }

  // ---- key exchange ----

  static KeyPair newKeyPair() {
    try {
      return KeyPairGenerator.getInstance("X25519").generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("X25519 is not available", e);
    }
  }

  /** The 32-byte little-endian u coordinate (RFC 7748). */
  static byte[] raw(KeyPair pair) {
    final byte[] big = ((XECPublicKey) pair.getPublic()).getU().toByteArray();
    final byte[] out = new byte[PUBLIC_KEY];
    for (int i = 0; i < Math.min(big.length, PUBLIC_KEY); i++) {
      out[i] = big[big.length - 1 - i];
    }
    return out;
  }

  /** The shared secret with the other side's raw public key; throws {@link NetDataException} for a bad key. */
  static byte[] agree(PrivateKey mine, byte[] theirs) {
    try {
      final byte[] big = new byte[PUBLIC_KEY + 1]; // a leading zero keeps it positive
      for (int i = 0; i < PUBLIC_KEY; i++) {
        big[PUBLIC_KEY - i] = theirs[i];
      }
      final var key = KeyFactory.getInstance("XDH")
          .generatePublic(new XECPublicKeySpec(NamedParameterSpec.X25519, new BigInteger(big)));
      final var agreement = KeyAgreement.getInstance("XDH");
      agreement.init(mine);
      agreement.doPhase(key, true);
      final byte[] secret = agreement.generateSecret();
      if (Arrays.equals(secret, new byte[secret.length])) {
        throw new NetDataException("weak public key");
      }
      return secret;
    } catch (java.security.InvalidKeyException | java.security.spec.InvalidKeySpecException e) {
      throw new NetDataException("bad public key");
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("X25519 failed", e);
    }
  }

  /** A key from the password (PBKDF2, salted with the protocol id), or zeros without one. */
  static byte[] passwordKey(String password, int protocolId) {
    if (password == null) {
      return new byte[32];
    }
    try {
      final var spec = new PBEKeySpec(password.toCharArray(), ("ens-net/" + protocolId).getBytes(
          StandardCharsets.UTF_8), 20_000, 256);
      return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("PBKDF2 is not available", e);
    }
  }

  /** What the client sends to show it knows the password. */
  static byte[] proof(byte[] passwordKey, long clientSalt, long cookie, byte[] clientPublic) {
    return Arrays.copyOf(hmac(passwordKey, "proof".getBytes(StandardCharsets.UTF_8),
        ByteBuffer.allocate(16).putLong(clientSalt).putLong(cookie).array(), clientPublic), PROOF);
  }

  static Keys keys(byte[] shared, byte[] passwordKey, long clientSalt, long cookie, byte[] clientPublic,
      byte[] serverPublic) {
    final byte[] salt = ByteBuffer.allocate(16).putLong(clientSalt).putLong(cookie).array();
    final byte[] prk = hmac(salt, shared, passwordKey, clientPublic, serverPublic);
    return new Keys(hmac(prk, "client to server".getBytes(StandardCharsets.UTF_8)),
        hmac(prk, "server to client".getBytes(StandardCharsets.UTF_8)));
  }

  private static byte[] hmac(byte[] key, byte[]... parts) {
    try {
      final var mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(key, "HmacSHA256"));
      for (byte[] p : parts) {
        mac.update(p);
      }
      return mac.doFinal();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("HmacSHA256 is not available", e);
    }
  }
}
