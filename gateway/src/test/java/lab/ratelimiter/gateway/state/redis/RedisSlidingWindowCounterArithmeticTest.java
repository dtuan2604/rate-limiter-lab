package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import lab.ratelimiter.gateway.application.RedisOutcome;
import org.junit.jupiter.api.Test;

class RedisSlidingWindowCounterArithmeticTest {

  @Test
  void initializesAtEpochAlignedWindowAndAdmitsExactCapacity() {
    SlidingCounterTransition transition =
        RedisSlidingWindowCounterArithmetic.decide(parameters(5, 1_000, 5), null, 10_250);

    assertThat(transition.allowed()).isTrue();
    assertThat(transition.state()).isEqualTo(new SlidingCounterState(10, 5, 0));
    assertThat(transition.currentWindowStartMilliseconds()).isEqualTo(10_000);
    assertThat(transition.elapsedMilliseconds()).isEqualTo(250);
    assertThat(transition.weightedNumerator()).isEqualTo(5_000);
    assertThat(transition.weightedEstimate()).isEqualTo(5);
    assertThat(transition.remainingCapacity()).isZero();
    assertThat(transition.resetAfterMilliseconds()).isEqualTo(1_750);
    assertThat(transition.rotation()).isEqualTo(SlidingCounterRotation.MISSING);
  }

  @Test
  void weightsPreviousAtBeginningMiddleAndFinalInstantWithoutFloatingPoint() {
    SlidingCounterParameters parameters = parameters(10, 1_000, 1);

    SlidingCounterTransition beginning =
        RedisSlidingWindowCounterArithmetic.decide(
            parameters, new SlidingCounterState(10, 0, 5), 10_000);
    assertThat(beginning.weightedNumerator()).isEqualTo(6_000);
    assertThat(beginning.weightedEstimate()).isEqualTo(6);

    SlidingCounterTransition middle =
        RedisSlidingWindowCounterArithmetic.decide(
            parameters, new SlidingCounterState(10, 2, 6), 10_500);
    assertThat(middle.weightedNumerator()).isEqualTo(6_000);

    SlidingCounterTransition finalInstant =
        RedisSlidingWindowCounterArithmetic.decide(
            parameters, new SlidingCounterState(10, 8, 10), 10_999);
    assertThat(finalInstant.allowed()).isTrue();
    assertThat(finalInstant.weightedNumerator()).isEqualTo(9_010);
    assertThat(finalInstant.weightedEstimate()).isEqualTo(10);
    assertThat(finalInstant.remainingCapacity()).isZero();
  }

  @Test
  void rotatesOnceAtExactBoundaryAndClearsAfterSeveralMissedWindows() {
    SlidingCounterParameters parameters = parameters(10, 1_000, 1);

    SlidingCounterTransition one =
        RedisSlidingWindowCounterArithmetic.decide(
            parameters, new SlidingCounterState(10, 4, 2), 11_000);
    assertThat(one.state()).isEqualTo(new SlidingCounterState(11, 1, 4));
    assertThat(one.rotation()).isEqualTo(SlidingCounterRotation.ADVANCE_ONE);

    SlidingCounterTransition many =
        RedisSlidingWindowCounterArithmetic.decide(parameters, one.state(), 13_000);
    assertThat(many.state()).isEqualTo(new SlidingCounterState(13, 1, 0));
    assertThat(many.rotation()).isEqualTo(SlidingCounterRotation.ADVANCE_MANY);
  }

  @Test
  void rejectionDoesNotIncrementAndRetryIsExactInCurrentOrFollowingWindow() {
    SlidingCounterParameters parameters = parameters(5, 1_000, 1);
    SlidingCounterTransition within =
        RedisSlidingWindowCounterArithmetic.decide(
            parameters, new SlidingCounterState(10, 3, 4), 10_500);
    assertThat(within.allowed()).isFalse();
    assertThat(within.state()).isEqualTo(new SlidingCounterState(10, 3, 4));
    assertThat(within.retryAfterMilliseconds()).isEqualTo(250);

    SlidingCounterTransition next =
        RedisSlidingWindowCounterArithmetic.decide(
            parameters, new SlidingCounterState(10, 5, 0), 10_500);
    assertThat(next.allowed()).isFalse();
    assertThat(next.retryAfterMilliseconds()).isEqualTo(700);
  }

  @Test
  void malformedStateRollbackAndUnsafeParametersFailWithoutADecision() {
    SlidingCounterParameters parameters = parameters(10, 1_000, 1);
    assertThatThrownBy(
            () ->
                RedisSlidingWindowCounterArithmetic.decide(
                    parameters, new SlidingCounterState(11, 1, 0), 10_999))
        .isInstanceOf(RedisStateException.class)
        .extracting(error -> ((RedisStateException) error).outcome())
        .isEqualTo(RedisOutcome.CLOCK_ROLLBACK);
    assertThatThrownBy(
            () ->
                RedisSlidingWindowCounterArithmetic.decide(
                    parameters, new SlidingCounterState(10, 11, 0), 10_000))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(() -> parameters(0, 1_000, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parameters(1_000_001, 1_000, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parameters(10, 0, 1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parameters(10, 86_400_001, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> parameters(10, 1_000, 11))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> RedisSlidingWindowCounterArithmetic.decide(parameters, null, -1))
        .isInstanceOf(RedisStateException.class);
  }

  private static SlidingCounterParameters parameters(long limit, long window, long cost) {
    return new SlidingCounterParameters(limit, window, cost);
  }
}
