package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.LongRange;

class RedisTokenBucketArithmeticPropertyTest {

  @Property(tries = 1_000)
  void boundedModelMatchesBigIntegerReference(
      @ForAll @IntRange(min = 1, max = 1_000) int capacity,
      @ForAll @IntRange(min = 0, max = 1_000) int initialCandidate,
      @ForAll @IntRange(min = 1, max = 1_000) int refillTokens,
      @ForAll @IntRange(min = 1, max = 10_000) int period,
      @ForAll @IntRange(min = 1, max = 1_000) int costCandidate,
      @ForAll @LongRange(min = 0, max = 2_592_000_000L) long elapsed) {
    long initial = Math.min(initialCandidate, capacity);
    long cost = Math.min(costCandidate, capacity);
    TokenBucketParameters parameters =
        TokenBucketParameters.ofTokens(capacity, initial, refillTokens, period, cost, 0);

    TokenBucketTransition actual = RedisTokenBucketArithmetic.decide(parameters, null, elapsed);
    ReferenceDecision reference = reference(parameters, elapsed);

    assertThat(actual.allowed()).isEqualTo(reference.allowed());
    assertThat(actual.state().tokensScaled()).isEqualTo(reference.remainingScaled());
    assertThat(actual.state().refillRemainder()).isEqualTo(reference.remainder());
    assertThat(actual.retryAfterMilliseconds()).isEqualTo(reference.retryMilliseconds());
    assertThat(actual.resetAfterMilliseconds()).isEqualTo(reference.resetMilliseconds());
  }

  private static ReferenceDecision reference(TokenBucketParameters parameters, long elapsed) {
    BigInteger period = BigInteger.valueOf(parameters.refillPeriodMilliseconds());
    BigInteger numerator =
        BigInteger.valueOf(elapsed).multiply(BigInteger.valueOf(parameters.refillTokensScaled()));
    BigInteger[] division = numerator.divideAndRemainder(period);
    long refilled =
        Math.min(
            parameters.capacityScaled(),
            Math.addExact(parameters.initialTokensScaled(), division[0].longValueExact()));
    long remainder = refilled == parameters.capacityScaled() ? 0 : division[1].longValueExact();
    boolean allowed = refilled >= parameters.requestCostScaled();
    long remaining = allowed ? refilled - parameters.requestCostScaled() : refilled;
    long retry =
        allowed
            ? 0
            : ceilTime(
                parameters.requestCostScaled() - remaining,
                remainder,
                parameters.refillTokensScaled(),
                parameters.refillPeriodMilliseconds());
    long reset =
        ceilTime(
            parameters.capacityScaled() - remaining,
            remainder,
            parameters.refillTokensScaled(),
            parameters.refillPeriodMilliseconds());
    return new ReferenceDecision(allowed, remaining, remainder, retry, reset);
  }

  private static long ceilTime(
      long neededScaled, long remainder, long refillScaled, long periodMilliseconds) {
    if (neededScaled == 0) {
      return 0;
    }
    BigInteger numerator =
        BigInteger.valueOf(neededScaled)
            .multiply(BigInteger.valueOf(periodMilliseconds))
            .subtract(BigInteger.valueOf(remainder));
    return numerator
        .add(BigInteger.valueOf(refillScaled - 1))
        .divide(BigInteger.valueOf(refillScaled))
        .longValueExact();
  }

  private record ReferenceDecision(
      boolean allowed,
      long remainingScaled,
      long remainder,
      long retryMilliseconds,
      long resetMilliseconds) {}
}
