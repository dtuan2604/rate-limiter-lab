package lab.ratelimiter.gateway.domain.limiter;

public record RequestCost(long units) {

  public RequestCost {
    ModelValidation.requirePositive(units, "request cost");
  }
}
