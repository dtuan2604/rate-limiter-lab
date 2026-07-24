package lab.ratelimiter.gateway.domain.limiter;

import java.util.Objects;

public record PolicyId(String value) {

  public PolicyId {
    Objects.requireNonNull(value, "value");
    if (value.isBlank()) {
      throw new IllegalArgumentException("policy ID must not be blank");
    }
  }
}
