package lab.ratelimiter.gateway.http;

import java.time.Duration;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import org.springframework.http.HttpHeaders;

final class RateLimitHeaders {

  private static final String LIMIT = "RateLimit-Limit";
  private static final String REMAINING = "RateLimit-Remaining";
  private static final String RESET = "RateLimit-Reset";
  private static final String POLICY = "X-RateLimit-Policy";
  private static final String POLICY_VERSION = "X-RateLimit-Policy-Version";
  private static final String CORRELATION_ID = "X-Correlation-Id";

  private RateLimitHeaders() {}

  static void apply(
      HttpHeaders headers,
      RateLimitDecision decision,
      String correlationId,
      Duration resetAfter,
      boolean includeRetryAfter) {
    headers.set(LIMIT, Long.toString(decision.limit()));
    headers.set(REMAINING, Long.toString(decision.remaining()));
    if (decision.resetAt().isPresent()) {
      headers.set(RESET, Long.toString(ceilingSeconds(resetAfter)));
    }
    headers.set(POLICY, decision.policyId().value());
    headers.set(POLICY_VERSION, Long.toString(decision.policyVersion().value()));
    headers.set(CORRELATION_ID, correlationId);
    if (includeRetryAfter) {
      decision
          .retryAfter()
          .ifPresent(
              retryAfter ->
                  headers.set(HttpHeaders.RETRY_AFTER, Long.toString(ceilingSeconds(retryAfter))));
    }
  }

  private static long ceilingSeconds(Duration duration) {
    long milliseconds = duration.toMillis();
    return Math.floorDiv(Math.addExact(milliseconds, 999), 1000);
  }
}
