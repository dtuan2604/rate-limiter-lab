package lab.ratelimiter.gateway.domain.limiter;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

public record RateLimitDecision(
    boolean allowed,
    long limit,
    long remaining,
    Optional<Duration> retryAfter,
    Optional<Instant> resetAt,
    PolicyId policyId,
    PolicyVersion policyVersion,
    AlgorithmType algorithm) {

  public RateLimitDecision {
    ModelValidation.requirePositive(limit, "limit");
    ModelValidation.requireNonNegative(remaining, "remaining");
    if (remaining > limit) {
      throw new IllegalArgumentException("remaining must not exceed limit");
    }
    Objects.requireNonNull(retryAfter, "retryAfter");
    Objects.requireNonNull(resetAt, "resetAt");
    Objects.requireNonNull(policyId, "policyId");
    Objects.requireNonNull(policyVersion, "policyVersion");
    Objects.requireNonNull(algorithm, "algorithm");
    retryAfter.ifPresent(value -> ModelValidation.requireNonNegative(value, "retry after"));
  }
}
