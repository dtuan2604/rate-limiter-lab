package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import org.junit.jupiter.api.Test;

class RedisFixedWindowKeyTest {

  @Test
  void keyContainsVersionAlgorithmHashAndServerWindowWithoutRawIdentity() {
    FixedWindowPolicy policy = policy("catalog-client-fixed-window", 7);
    LimiterIdentity identity =
        new ClientIdentityExtractor().extract("secret-client", "catalog.items").orElseThrow();

    RedisFixedWindowKey key = RedisFixedWindowKey.create(policy, identity, 12345);

    String encodedPolicy =
        Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(policy.policyId().value().getBytes(StandardCharsets.UTF_8));
    assertThat(key.value())
        .isEqualTo(
            "ratelimit:{p="
                + encodedPolicy
                + ":v=7:a=fixed-window:i="
                + identity.digest()
                + "}:w=12345")
        .doesNotContain("secret-client", "catalog.items");
    assertThat(key.windowId()).isEqualTo(12345);
  }

  @Test
  void policyVersionsAndIdentitiesProduceIndependentKeys() {
    ClientIdentityExtractor extractor = new ClientIdentityExtractor();
    LimiterIdentity first = extractor.extract("client-a", "catalog.items").orElseThrow();
    LimiterIdentity second = extractor.extract("client-b", "catalog.items").orElseThrow();

    assertThat(RedisFixedWindowKey.create(policy("policy", 1), first, 9))
        .isNotEqualTo(RedisFixedWindowKey.create(policy("policy", 2), first, 9))
        .isNotEqualTo(RedisFixedWindowKey.create(policy("policy", 1), second, 9))
        .isNotEqualTo(RedisFixedWindowKey.create(policy("policy", 1), first, 10));
  }

  @Test
  void negativeWindowIdentifierIsRejected() {
    LimiterIdentity identity =
        new ClientIdentityExtractor().extract("client", "catalog.items").orElseThrow();

    assertThatThrownBy(() -> RedisFixedWindowKey.create(policy("policy", 1), identity, -1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("window");

    assertThatThrownBy(() -> new RedisFixedWindowKey(" ", 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RedisFixedWindowKey("key", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RedisFixedWindowKey.create(null, identity, 1))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> RedisFixedWindowKey.create(policy("policy", 1), null, 1))
        .isInstanceOf(NullPointerException.class);
  }

  private static FixedWindowPolicy policy(String id, long version) {
    return new FixedWindowPolicy(
        new PolicyId(id), new PolicyVersion(version), 5, Duration.ofSeconds(10));
  }
}
