package lab.ratelimiter.gateway.domain.limiter;

public interface RateLimiter<P extends RateLimitPolicy, S extends RateLimitState> {

  P policy();

  S snapshot();

  RateLimitDecision decide(RateLimitRequest request);
}
