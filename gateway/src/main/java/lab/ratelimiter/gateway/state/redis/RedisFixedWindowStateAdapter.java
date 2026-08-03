package lab.ratelimiter.gateway.state.redis;

import io.lettuce.core.RedisConnectionException;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeoutException;
import lab.ratelimiter.gateway.application.FixedWindowStateAdapter;
import lab.ratelimiter.gateway.application.FixedWindowStateResult;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import reactor.core.Exceptions;
import reactor.core.publisher.Mono;

public final class RedisFixedWindowStateAdapter implements FixedWindowStateAdapter {

  private static final int MAX_WINDOW_MISMATCH_ATTEMPTS = 3;

  private final ReactiveStringRedisTemplate redis;
  private final RedisScript<List<?>> script;
  private final Duration commandTimeout;

  public RedisFixedWindowStateAdapter(
      ReactiveStringRedisTemplate redis, RedisScript<List<?>> script, Duration commandTimeout) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.script = Objects.requireNonNull(script, "script");
    this.commandTimeout = Objects.requireNonNull(commandTimeout, "commandTimeout");
    if (commandTimeout.isZero() || commandTimeout.isNegative()) {
      throw new IllegalArgumentException("command timeout must be positive");
    }
  }

  @Override
  public Mono<FixedWindowStateResult> decide(
      FixedWindowPolicy policy, LimiterIdentity identity, RateLimitRequest request) {
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(identity, "identity");
    Objects.requireNonNull(request, "request");
    validate(policy, request);

    return redis
        .execute(connection -> connection.serverCommands().time())
        .single()
        .timeout(commandTimeout)
        .flatMap(
            redisNow ->
                execute(
                    policy,
                    identity,
                    request,
                    Math.floorDiv(redisNow, policy.window().toMillis()),
                    1))
        .onErrorMap(RedisFixedWindowStateAdapter::classify);
  }

  private Mono<FixedWindowStateResult> execute(
      FixedWindowPolicy policy,
      LimiterIdentity identity,
      RateLimitRequest request,
      long candidateWindowId,
      int attempt) {
    RedisFixedWindowKey key = RedisFixedWindowKey.create(policy, identity, candidateWindowId);
    List<String> arguments =
        List.of(
            "1",
            Long.toString(policy.limit()),
            Long.toString(policy.window().toMillis()),
            Long.toString(request.cost().units()),
            Long.toString(candidateWindowId));

    return redis
        .execute(script, List.of(key.value()), arguments.toArray())
        .single()
        .timeout(commandTimeout)
        .map(RedisFixedWindowScriptResult::decode)
        .flatMap(
            result -> {
              if (!result.windowMismatch()) {
                return Mono.just(result.toStateResult(policy));
              }
              if (attempt >= MAX_WINDOW_MISMATCH_ATTEMPTS) {
                return Mono.error(
                    new RedisStateException(
                        RedisOutcome.WINDOW_MISMATCH_EXHAUSTED,
                        "Redis window validation did not converge"));
              }
              return execute(policy, identity, request, result.windowId(), attempt + 1);
            });
  }

  static void validate(FixedWindowPolicy policy, RateLimitRequest request) {
    long windowMilliseconds = policy.window().toMillis();
    if (policy.limit() < 1 || policy.limit() > 1_000_000) {
      throw new IllegalArgumentException("Redis fixed-window limit is outside supported bounds");
    }
    if (windowMilliseconds < 1 || windowMilliseconds > Duration.ofDays(1).toMillis()) {
      throw new IllegalArgumentException("Redis fixed-window duration is outside supported bounds");
    }
    if (request.cost().units() != 1) {
      throw new IllegalArgumentException("Redis fixed-window request cost must be one");
    }
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
          RedisOutcome.MALFORMED_STATE, "Redis fixed-window state is malformed", unwrapped);
    }
    if (unwrapped instanceof DataAccessException || hasMessage(unwrapped, "RATE_LIMIT_SCRIPT")) {
      return new RedisStateException(
          RedisOutcome.SCRIPT_ERROR, "Redis script execution failed", unwrapped);
    }
    return new RedisStateException(
        RedisOutcome.MALFORMED_RESPONSE, "Redis response was invalid", unwrapped);
  }

  private static boolean hasMessage(Throwable throwable, String fragment) {
    for (Throwable current = throwable; current != null; current = current.getCause()) {
      if (current.getMessage() != null && current.getMessage().contains(fragment)) {
        return true;
      }
    }
    return false;
  }
}
