package lab.ratelimiter.gateway.policy.control;

public enum PolicyAlgorithmType {
  FIXED_WINDOW,
  TOKEN_BUCKET,
  SLIDING_WINDOW_COUNTER
}
