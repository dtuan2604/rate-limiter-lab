package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import org.junit.jupiter.api.Test;

class RedisSlidingWindowCounterKeyTest {

  @Test
  void keyContainsEncodedPolicyVersionAlgorithmAndOnlyIdentityDigest() {
    SlidingWindowCounterPolicy policy =
        new SlidingWindowCounterPolicy(
            new PolicyId("catalog/sliding"), new PolicyVersion(7), 100, Duration.ofSeconds(60));
    var identity =
        new ClientIdentityExtractor().extract("raw-client-secret", "catalog.items").orElseThrow();

    String key = RedisSlidingWindowCounterKey.create(policy, identity).value();

    assertThat(key)
        .startsWith("ratelimit:{p=")
        .contains(":v=7:a=sliding-window-counter:i=" + identity.digest() + "}")
        .doesNotContain("catalog/sliding")
        .doesNotContain("raw-client-secret");
  }
}
