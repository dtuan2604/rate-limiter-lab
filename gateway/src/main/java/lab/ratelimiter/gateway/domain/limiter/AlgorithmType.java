package lab.ratelimiter.gateway.domain.limiter;

public enum AlgorithmType {
  FIXED_WINDOW,
  SLIDING_WINDOW_LOG,
  SLIDING_WINDOW_COUNTER,
  TOKEN_BUCKET,
  LEAKY_BUCKET
}
