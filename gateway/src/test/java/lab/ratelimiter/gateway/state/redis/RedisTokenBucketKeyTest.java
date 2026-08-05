package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import org.junit.jupiter.api.Test;

class RedisTokenBucketKeyTest {

  @Test
  void keyContainsEncodedPolicyVersionAlgorithmAndOnlyIdentityDigest() {
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            new PolicyId("catalog/token"), new PolicyVersion(7), 10, 4, 2, Duration.ofSeconds(1));
    var identity =
        new ClientIdentityExtractor().extract("raw-client-secret", "catalog.items").orElseThrow();

    String key = RedisTokenBucketKey.create(policy, identity).value();

    assertThat(key)
        .startsWith("ratelimit:{p=")
        .contains(":v=7:a=token-bucket:i=" + identity.digest() + "}")
        .doesNotContain("catalog/token")
        .doesNotContain("raw-client-secret");
  }
}
