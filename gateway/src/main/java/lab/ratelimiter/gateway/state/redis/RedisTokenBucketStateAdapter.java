package lab.ratelimiter.gateway.state.redis;

import io.lettuce.core.RedisConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.TokenBucketStateAdapter;
import lab.ratelimiter.gateway.application.TokenBucketStateResult;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

public final class RedisTokenBucketStateAdapter implements TokenBucketStateAdapter {

  private final Duration commandTimeout;
  private final ScriptExecutor scriptExecutor;

  public RedisTokenBucketStateAdapter(
      ReactiveStringRedisTemplate redis, RedisScript<List<?>> script, Duration commandTimeout) {
    this(createScriptExecutor(redis, script), commandTimeout);
  }

  RedisTokenBucketStateAdapter(ScriptExecutor scriptExecutor, Duration commandTimeout) {
    this.scriptExecutor = Objects.requireNonNull(scriptExecutor, "scriptExecutor");
    this.commandTimeout = validateTimeout(commandTimeout);
  }

  private static ScriptExecutor createScriptExecutor(
      ReactiveStringRedisTemplate redis, RedisScript<List<?>> script) {
    Objects.requireNonNull(redis, "redis");
    Objects.requireNonNull(script, "script");
    return (key, arguments) -> redis.execute(script, List.of(key), arguments.toArray()).single();
  }

  private static Duration validateTimeout(Duration commandTimeout) {
    Objects.requireNonNull(commandTimeout, "commandTimeout");
    if (commandTimeout.isZero() || commandTimeout.isNegative()) {
      throw new IllegalArgumentException("command timeout must be positive");
    }
    return commandTimeout;
  }

  @Override
  public Mono<TokenBucketStateResult> decide(
      TokenBucketPolicy policy,
      long requestCost,
      Instant activationTime,
      LimiterIdentity identity) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(activationTime, "activationTime");
    Objects.requireNonNull(identity, "identity");
    TokenBucketParameters parameters =
        TokenBucketParameters.ofTokens(
            policy.capacity(),
            policy.initialTokens(),
            policy.refillTokens(),
            policy.refillPeriod().toMillis(),
            requestCost,
            activationTime.toEpochMilli());
    RedisTokenBucketKey key = RedisTokenBucketKey.create(policy, identity);
    List<String> arguments =
        List.of(
            "1",
            Long.toString(parameters.capacityScaled()),
            Long.toString(parameters.initialTokensScaled()),
            Long.toString(parameters.refillTokensScaled()),
            Long.toString(parameters.refillPeriodMilliseconds()),
            Long.toString(parameters.requestCostScaled()),
            Long.toString(parameters.activationMilliseconds()),
            Long.toString(TokenBucketParameters.MAXIMUM_CLOCK_ROLLBACK_MILLISECONDS));
    return scriptExecutor
        .execute(key.value(), arguments)
        .timeout(commandTimeout)
        .map(tuple -> RedisTokenBucketScriptResult.decode(tuple, parameters))
        .map(result -> result.toStateResult(policy))
        .onErrorMap(RedisTokenBucketStateAdapter::classify);
  }

  static Throwable classify(Throwable throwable) {
    Throwable unwrapped = Exceptions.unwrap(throwable);
    if (unwrapped instanceof RedisStateException) {
      return unwrapped;
    }
    if (unwrapped instanceof TimeoutException) {
      return new RedisStateException(RedisOutcome.TIMEOUT, "Redis command timed out", unwrapped);
    }
    if (unwrapped instanceof RedisConnectionFailureException
        || unwrapped instanceof RedisConnectionException) {
      return new RedisStateException(
          RedisOutcome.CONNECTION_FAILURE, "Redis connection is unavailable", unwrapped);
    }
    if (hasMessage(unwrapped, "RATE_LIMIT_STATE_MALFORMED")) {
      return new RedisStateException(
          RedisOutcome.MALFORMED_STATE, "Redis Token Bucket state is malformed", unwrapped);
    }
    if (unwrapped instanceof DataAccessException || hasMessage(unwrapped, "RATE_LIMIT_SCRIPT")) {
      return new RedisStateException(
          RedisOutcome.SCRIPT_ERROR, "Redis Token Bucket script execution failed", unwrapped);
    }
    return new RedisStateException(
        RedisOutcome.MALFORMED_RESPONSE, "Redis Token Bucket response was invalid", unwrapped);
  }

  private static boolean hasMessage(Throwable throwable, String fragment) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current.getMessage() != null && current.getMessage().contains(fragment)) {
        return true;
      }
    }
    return false;
  }

  @FunctionalInterface
  interface ScriptExecutor {

    Mono<List<?>> execute(String key, List<String> arguments);
  }
}
