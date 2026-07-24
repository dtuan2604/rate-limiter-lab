package lab.ratelimiter.gateway.domain.limiter;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Objects;

final class ModelValidation {

  private static final Duration MAX_MILLISECONDS = Duration.ofMillis(Long.MAX_VALUE);

  private ModelValidation() {}

  static void requirePositive(long value, String name) {
    if (value <= 0) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }

  static void requireNonNegative(long value, String name) {
    if (value < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  static void requireNonNegative(BigInteger value, String name) {
    if (value.signum() < 0) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  static void requireNonNegative(Duration value, String name) {
    if (value.isNegative()) {
      throw new IllegalArgumentException(name + " must not be negative");
    }
  }

  static void requirePositiveWholeMilliseconds(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative() || value.compareTo(MAX_MILLISECONDS) > 0) {
      throw new IllegalArgumentException(name + " must be a positive millisecond duration");
    }
    long milliseconds = value.toMillis();
    if (!value.equals(Duration.ofMillis(milliseconds))) {
      throw new IllegalArgumentException(name + " must use whole milliseconds");
    }
  }
}
