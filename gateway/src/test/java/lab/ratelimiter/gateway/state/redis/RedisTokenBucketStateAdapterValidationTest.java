package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import io.lettuce.core.RedisConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import lab.ratelimiter.gateway.application.RedisOutcome;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class RedisTokenBucketStateAdapterValidationTest {

  @Test
  void constructorRejectsInvalidDependenciesAndTimeouts() {
    ReactiveStringRedisTemplate redis = mock(ReactiveStringRedisTemplate.class);
    RedisScript<List<?>> script = mockScript();
    assertThatThrownBy(() -> new RedisTokenBucketStateAdapter(null, script, Duration.ofSeconds(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RedisTokenBucketStateAdapter(redis, null, Duration.ofSeconds(1)))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RedisTokenBucketStateAdapter(redis, script, null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new RedisTokenBucketStateAdapter(redis, script, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RedisTokenBucketStateAdapter(redis, script, Duration.ofMillis(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void classifiesEveryRedisFailureUsingSanitizedOutcomes() {
    RedisStateException existing =
        new RedisStateException(RedisOutcome.MALFORMED_RESPONSE, "already sanitized");
    assertThat(RedisTokenBucketStateAdapter.classify(existing)).isSameAs(existing);
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
    assertThat(RedisTokenBucketStateAdapter.classify(failure))
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
}
