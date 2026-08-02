package lab.ratelimiter.gateway.http;

import java.util.Objects;

record RateLimitErrorResponse(
    int status,
    String error,
    String message,
    String policy,
    long retryAfterMilliseconds,
    String correlationId) {

  RateLimitErrorResponse {
    Objects.requireNonNull(error, "error");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(policy, "policy");
    Objects.requireNonNull(correlationId, "correlationId");
  }
}
