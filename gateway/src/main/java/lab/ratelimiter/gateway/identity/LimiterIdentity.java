package lab.ratelimiter.gateway.identity;

import java.util.Objects;

public record LimiterIdentity(String digest) {

  public LimiterIdentity {
    Objects.requireNonNull(digest, "digest");
    if (!digest.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("identity digest must be lowercase SHA-256 hex");
    }
  }
}
