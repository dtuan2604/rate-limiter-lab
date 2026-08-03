package lab.ratelimiter.gateway.identity;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;

public final class ClientIdentityExtractor {

  private static final int MAXIMUM_CLIENT_ID_CHARACTERS = 256;
  private static final int MAXIMUM_ROUTE_ID_CHARACTERS = 128;

  public Optional<LimiterIdentity> extract(String clientId, String routeId) {
    if (clientId == null || clientId.isBlank()) {
      return Optional.empty();
    }
    requireBounded(clientId, MAXIMUM_CLIENT_ID_CHARACTERS, "client ID");
    Objects.requireNonNull(routeId, "routeId");
    requireBounded(routeId, MAXIMUM_ROUTE_ID_CHARACTERS, "route ID");

    MessageDigest digest = sha256();
    updateLengthDelimited(digest, "HEADER");
    updateLengthDelimited(digest, "x-client-id");
    updateLengthDelimited(digest, clientId);
    updateLengthDelimited(digest, "ROUTE_ID");
    updateLengthDelimited(digest, routeId);
    return Optional.of(new LimiterIdentity(HexFormat.of().formatHex(digest.digest())));
  }

  private static void requireBounded(String value, int maximum, String name) {
    if (value.length() > maximum) {
      throw new IllegalArgumentException(name + " exceeds " + maximum + " characters");
    }
  }

  private static MessageDigest sha256() {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("Java runtime does not provide SHA-256", exception);
    }
  }

  private static void updateLengthDelimited(MessageDigest digest, String value) {
    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
    digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
    digest.update(bytes);
  }
}
