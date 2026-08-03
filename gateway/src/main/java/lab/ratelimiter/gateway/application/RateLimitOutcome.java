package lab.ratelimiter.gateway.application;

public enum RateLimitOutcome {
  ALLOW,
  REJECT,
  DEGRADED_ALLOW,
  STATE_UNAVAILABLE
}
