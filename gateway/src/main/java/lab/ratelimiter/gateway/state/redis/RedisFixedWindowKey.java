package lab.ratelimiter.gateway.state.redis;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;

public record RedisFixedWindowKey(String value, long windowId) {

  public RedisFixedWindowKey {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("Redis key must not be blank");
    }
    if (windowId < 0) {
      throw new IllegalArgumentException("window identifier must not be negative");
    }
  }

  public static RedisFixedWindowKey create(
      FixedWindowPolicy policy, LimiterIdentity identity, long windowId) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(identity, "identity");
    if (windowId < 0) {
      throw new IllegalArgumentException("window identifier must not be negative");
    }
    String encodedPolicy =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(policy.policyId().value().getBytes(StandardCharsets.UTF_8));
    String key =
        "ratelimit:{p="
            + encodedPolicy
            + ":v="
            + policy.policyVersion().value()
            + ":a=fixed-window:i="
            + identity.digest()
            + "}:w="
            + windowId;
    return new RedisFixedWindowKey(key, windowId);
  }
}
