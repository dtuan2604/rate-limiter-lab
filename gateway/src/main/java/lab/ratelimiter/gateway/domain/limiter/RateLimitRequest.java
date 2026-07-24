package lab.ratelimiter.gateway.domain.limiter;

import java.util.Objects;

public record RateLimitRequest(RequestCost cost) {

  public RateLimitRequest {
    Objects.requireNonNull(cost, "cost");
  }

  public RateLimitRequest(long cost) {
    this(new RequestCost(cost));
  }
}
