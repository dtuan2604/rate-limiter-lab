package lab.ratelimiter.gateway.state.redis;

import io.lettuce.core.RedisConnectionException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeoutException;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.SlidingWindowCounterStateAdapter;
import lab.ratelimiter.gateway.application.SlidingWindowCounterStateResult;
import lab.ratelimiter.gateway.application.StateBackend;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

public final class RedisSlidingWindowCounterStateAdapter
    implements SlidingWindowCounterStateAdapter {

  private final Duration commandTimeout;
  private final ScriptExecutor scriptExecutor;

  public RedisSlidingWindowCounterStateAdapter(
      ReactiveStringRedisTemplate redis, RedisScript<List<?>> script, Duration commandTimeout) {
    this(createScriptExecutor(redis, script), commandTimeout);
  }

  RedisSlidingWindowCounterStateAdapter(ScriptExecutor scriptExecutor, Duration commandTimeout) {
    this.scriptExecutor = Objects.requireNonNull(scriptExecutor, "scriptExecutor");
    this.commandTimeout = validateTimeout(commandTimeout);
  }

  @Override
  public Mono<SlidingWindowCounterStateResult> decide(
      SlidingWindowCounterPolicy policy, long requestCost, LimiterIdentity identity) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(identity, "identity");
    SlidingCounterParameters parameters =
        new SlidingCounterParameters(policy.limit(), policy.window().toMillis(), requestCost);
    RedisSlidingWindowCounterKey key = RedisSlidingWindowCounterKey.create(policy, identity);
    List<String> arguments =
        List.of(
            "1",
            Long.toString(parameters.limit()),
            Long.toString(parameters.windowMilliseconds()),
            Long.toString(parameters.requestCost()));
    return scriptExecutor
        .execute(key.value(), arguments)
        .timeout(commandTimeout)
        .map(tuple -> RedisSlidingWindowCounterScriptResult.decode(tuple, parameters))
        .map(result -> toStateResult(policy, result))
        .onErrorMap(RedisSlidingWindowCounterStateAdapter::classify);
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

  private static SlidingWindowCounterStateResult toStateResult(
      SlidingWindowCounterPolicy policy, RedisSlidingWindowCounterScriptResult result) {
    boolean allowed = result.outcome() == RedisOutcome.ALLOWED;
    Duration retry = Duration.ofMillis(result.retryAfterMilliseconds());
    Duration reset = Duration.ofMillis(result.resetAfterMilliseconds());
    Instant now = Instant.ofEpochMilli(result.redisNowMilliseconds());
    RateLimitDecision decision =
        new RateLimitDecision(
            allowed,
            result.limit(),
            result.remainingCapacity(),
            allowed ? Optional.empty() : Optional.of(retry),
            Optional.of(now.plus(reset)),
            policy.policyId(),
            policy.policyVersion(),
            policy.algorithm());
    return new SlidingWindowCounterStateResult(
        decision,
        result.currentWindowId(),
        result.currentWindowCount(),
        result.previousWindowCount(),
        Duration.ofMillis(result.elapsedMilliseconds()),
        result.weightedNumerator(),
        result.weightedEstimate(),
        result.requestCost(),
        result.remainingCapacity(),
        retry,
        reset,
        now,
        Duration.ofMillis(result.ttlMilliseconds()),
        result.rotation(),
        StateBackend.REDIS,
        result.outcome());
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
    if (hasMessage(unwrapped, "RATE_LIMIT_CLOCK_ROLLBACK")) {
      return new RedisStateException(
          RedisOutcome.CLOCK_ROLLBACK, "Redis Sliding Counter clock moved backward", unwrapped);
    }
    if (hasMessage(unwrapped, "RATE_LIMIT_STATE_MALFORMED")) {
      return new RedisStateException(
          RedisOutcome.MALFORMED_STATE, "Redis Sliding Counter state is malformed", unwrapped);
    }
    if (unwrapped instanceof DataAccessException || hasMessage(unwrapped, "RATE_LIMIT_SCRIPT")) {
      return new RedisStateException(
          RedisOutcome.SCRIPT_ERROR, "Redis Sliding Counter script execution failed", unwrapped);
    }
    return new RedisStateException(
        RedisOutcome.MALFORMED_RESPONSE, "Redis Sliding Counter response was invalid", unwrapped);
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
