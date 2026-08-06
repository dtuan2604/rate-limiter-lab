package lab.ratelimiter.gateway.state.redis;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;

public record RedisSlidingWindowCounterKey(String value) {

  public RedisSlidingWindowCounterKey {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("Redis key must not be blank");
    }
  }

  public static RedisSlidingWindowCounterKey create(
      SlidingWindowCounterPolicy policy, LimiterIdentity identity) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(identity, "identity");
    String encodedPolicy =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(policy.policyId().value().getBytes(StandardCharsets.UTF_8));
    return new RedisSlidingWindowCounterKey(
        "ratelimit:{p="
            + encodedPolicy
            + ":v="
            + policy.policyVersion().value()
            + ":a=sliding-window-counter:i="
            + identity.digest()
            + "}");
  }
}
