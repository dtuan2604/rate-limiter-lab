package lab.ratelimiter.gateway.policy;

public enum PolicyEventOutcome {
  REFRESHED,
  IGNORED,
  REJECTED,
  PAUSED
}
