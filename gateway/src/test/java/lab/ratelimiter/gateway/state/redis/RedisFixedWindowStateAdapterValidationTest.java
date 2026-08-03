package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.lettuce.core.RedisConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisFixedWindowStateAdapterValidationTest {

  private static final FixedWindowPolicy POLICY =
      new FixedWindowPolicy(
          new PolicyId("policy"), new PolicyVersion(1), 5, Duration.ofSeconds(10));

  @Test
  void constructorAndDistributedArithmeticBoundsFailBeforeRedis() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    RedisScript<List<?>> script = mockScript();

    assertThatThrownBy(() -> new RedisFixedWindowStateAdapter(redis, script, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RedisFixedWindowStateAdapter(redis, script, Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                RedisFixedWindowStateAdapter.validate(
                    policy(1_000_001, Duration.ofSeconds(10)), new RateLimitRequest(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit");
    assertThatThrownBy(
            () ->
                RedisFixedWindowStateAdapter.validate(
                    policy(5, Duration.ofDays(2)), new RateLimitRequest(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duration");
    assertThatThrownBy(() -> RedisFixedWindowStateAdapter.validate(POLICY, new RateLimitRequest(2)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cost");
  }

  @Test
  void classifiesEveryRedisFailureWithoutExposingItsDetailAsThePublicMessage() {
    RedisStateException existing =
        new RedisStateException(RedisOutcome.MALFORMED_RESPONSE, "already sanitized");
    assertThat(RedisFixedWindowStateAdapter.classify(existing)).isSameAs(existing);
    assertOutcome(new TimeoutException("secret"), RedisOutcome.TIMEOUT);
    assertOutcome(new RedisConnectionFailureException("secret"), RedisOutcome.CONNECTION_FAILURE);
    assertOutcome(new RedisConnectionException("secret"), RedisOutcome.CONNECTION_FAILURE);
    assertOutcome(
        new IllegalStateException(
            "outer", new IllegalStateException("RATE_LIMIT_STATE_MALFORMED secret")),
        RedisOutcome.MALFORMED_STATE);
    assertOutcome(new DataAccessResourceFailureException("secret"), RedisOutcome.SCRIPT_ERROR);
    assertOutcome(
        new IllegalStateException("RATE_LIMIT_SCRIPT_ARGUMENT secret"), RedisOutcome.SCRIPT_ERROR);
    assertOutcome(new IllegalStateException((String) null), RedisOutcome.MALFORMED_RESPONSE);
  }

  private static void assertOutcome(Throwable failure, RedisOutcome expected) {
    assertThat(RedisFixedWindowStateAdapter.classify(failure))
        .isInstanceOf(RedisStateException.class)
        .satisfies(
            classified -> {
              RedisStateException stateFailure = (RedisStateException) classified;
              assertThat(stateFailure.outcome()).isEqualTo(expected);
              assertThat(stateFailure.getMessage()).doesNotContain("secret");
            });
  }

  @SuppressWarnings("unchecked")
  private static RedisScript<List<?>> mockScript() {
    return (RedisScript<List<?>>) (RedisScript<?>) mock(RedisScript.class);
  }

  private static FixedWindowPolicy policy(long limit, Duration window) {
    return new FixedWindowPolicy(new PolicyId("policy"), new PolicyVersion(1), limit, window);
  }
}
