package lab.ratelimiter.gateway.state.redis;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;

public record RedisTokenBucketKey(String value) {

  public RedisTokenBucketKey {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("Redis key must not be blank");
    }
  }

  public static RedisTokenBucketKey create(TokenBucketPolicy policy, LimiterIdentity identity) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(identity, "identity");
    String encodedPolicy =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(policy.policyId().value().getBytes(StandardCharsets.UTF_8));
    return new RedisTokenBucketKey(
        "ratelimit:{p="
            + encodedPolicy
            + ":v="
            + policy.policyVersion().value()
            + ":a=token-bucket:i="
            + identity.digest()
            + "}");
  }
}
