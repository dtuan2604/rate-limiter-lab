package lab.ratelimiter.gateway.domain.limiter;

public record PolicyVersion(long value) {

  public PolicyVersion {
    ModelValidation.requirePositive(value, "policy version");
  }
}
