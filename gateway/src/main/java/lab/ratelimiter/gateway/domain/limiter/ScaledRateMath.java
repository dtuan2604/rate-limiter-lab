package lab.ratelimiter.gateway.domain.limiter;

import java.math.BigInteger;

final class ScaledRateMath {

  private ScaledRateMath() {}

  static BigInteger scale(long units, long periodMilliseconds) {
    return BigInteger.valueOf(units).multiply(BigInteger.valueOf(periodMilliseconds));
  }

  static long ceilDivide(BigInteger numerator, long denominator) {
    BigInteger divisor = BigInteger.valueOf(denominator);
    return numerator.add(divisor).subtract(BigInteger.ONE).divide(divisor).longValueExact();
  }
}
