package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RedisTokenBucketArithmeticTest {

  @Test
  void noElapsedTimeUsesInitialBalanceAndDoesNotCreditTokens() {
    TokenBucketParameters parameters = parameters(10, 4, 2, 1_000, 3, 10_000);

    TokenBucketTransition transition = RedisTokenBucketArithmetic.decide(parameters, null, 10_000);

    assertThat(transition.allowed()).isTrue();
    assertThat(transition.state().tokensScaled()).isEqualTo(1_000);
    assertThat(transition.state().lastMilliseconds()).isEqualTo(10_000);
    assertThat(transition.state().refillRemainder()).isZero();
    assertThat(transition.reconstructed()).isTrue();
    assertThat(transition.retryAfterMilliseconds()).isZero();
    assertThat(transition.resetAfterMilliseconds()).isEqualTo(4_500);
  }

  @Test
  void completeAndPartialPeriodsCarryRemainderConservatively() {
    TokenBucketParameters parameters = parameters(10, 0, 1, 3, 1, 0);

    TokenBucketTransition first = RedisTokenBucketArithmetic.decide(parameters, null, 1);
    assertThat(first.allowed()).isFalse();
    assertThat(first.state().tokensScaled()).isEqualTo(333);
    assertThat(first.state().refillRemainder()).isEqualTo(1);

    TokenBucketTransition second = RedisTokenBucketArithmetic.decide(parameters, first.state(), 2);
    assertThat(second.allowed()).isFalse();
    assertThat(second.state().tokensScaled()).isEqualTo(666);
    assertThat(second.state().refillRemainder()).isEqualTo(2);

    TokenBucketTransition third = RedisTokenBucketArithmetic.decide(parameters, second.state(), 3);
    assertThat(third.allowed()).isTrue();
    assertThat(third.state().tokensScaled()).isZero();
    assertThat(third.state().refillRemainder()).isZero();
  }

  @Test
  void severalPeriodsClampAtCapacityAndDiscardSurplusRemainder() {
    TokenBucketParameters parameters = parameters(5, 1, 2, 1_000, 1, 0);

    TokenBucketTransition transition = RedisTokenBucketArithmetic.decide(parameters, null, 60_000);

    assertThat(transition.allowed()).isTrue();
    assertThat(transition.state().tokensScaled()).isEqualTo(4_000);
    assertThat(transition.state().refillRemainder()).isZero();
    assertThat(transition.resetAfterMilliseconds()).isEqualTo(500);
  }

  @Test
  void costGreaterThanOneDeductsExactlyAndRejectionPreservesBalance() {
    TokenBucketParameters parameters = parameters(10, 10, 1, 1_000, 3, 0);
    TokenBucketState state = null;
    for (int attempt = 0; attempt < 3; attempt++) {
      TokenBucketTransition allowed = RedisTokenBucketArithmetic.decide(parameters, state, 0);
      assertThat(allowed.allowed()).isTrue();
      state = allowed.state();
    }
    assertThat(state.tokensScaled()).isEqualTo(1_000);

    TokenBucketTransition rejected = RedisTokenBucketArithmetic.decide(parameters, state, 0);

    assertThat(rejected.allowed()).isFalse();
    assertThat(rejected.state().tokensScaled()).isEqualTo(1_000);
    assertThat(rejected.retryAfterMilliseconds()).isEqualTo(2_000);
  }

  @Test
  void exactBoundaryBalanceIsAllowed() {
    TokenBucketParameters parameters = parameters(10, 3, 1, 1_000, 3, 0);

    TokenBucketTransition transition = RedisTokenBucketArithmetic.decide(parameters, null, 0);

    assertThat(transition.allowed()).isTrue();
    assertThat(transition.state().tokensScaled()).isZero();
  }

  @Test
  void ordinaryRollbackClampsWithoutMovingTimestampAndLargeRollbackFails() {
    TokenBucketParameters parameters = parameters(10, 10, 1, 1_000, 1, 0);
    TokenBucketState state = new TokenBucketState(5_000, 1_000_000, 0);

    TokenBucketTransition clamped = RedisTokenBucketArithmetic.decide(parameters, state, 900_000);

    assertThat(clamped.allowed()).isTrue();
    assertThat(clamped.state().tokensScaled()).isEqualTo(4_000);
    assertThat(clamped.state().lastMilliseconds()).isEqualTo(1_000_000);
    assertThat(clamped.resetAfterMilliseconds()).isEqualTo(106_000);

    assertThatThrownBy(() -> RedisTokenBucketArithmetic.decide(parameters, state, 699_999))
        .isInstanceOf(RedisStateException.class)
        .hasMessageContaining("rollback");
  }

  @Test
  void maximumValuesAndVeryLongIdleIntervalsRemainBounded() {
    TokenBucketParameters maximum = parameters(100_000, 0, 100_000, 86_400_000, 100_000, 0);

    TokenBucketTransition transition =
        RedisTokenBucketArithmetic.decide(maximum, null, Long.MAX_VALUE);

    assertThat(transition.allowed()).isTrue();
    assertThat(transition.state().tokensScaled()).isZero();
    assertThat(transition.resetAfterMilliseconds()).isEqualTo(86_400_000);
  }

  @Test
  void malformedStateAndUnsafeParametersFailBeforeDecision() {
    TokenBucketParameters parameters = parameters(10, 4, 2, 1_000, 1, 0);
    assertThatThrownBy(
            () ->
                RedisTokenBucketArithmetic.decide(
                    parameters, new TokenBucketState(10_001, 0, 0), 0))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(
            () ->
                RedisTokenBucketArithmetic.decide(
                    parameters, new TokenBucketState(1_000, 0, 1_000), 0))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(() -> TokenBucketParameters.ofTokens(100_001, 0, 1, 1_000, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(999, 0, 1_000, 1, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(100_000_001, 0, 1_000, 1, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, -1, 1_000, 1, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, 1_001, 1_000, 1, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, 0, 999, 1, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, 0, 100_000_001, 1, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, 0, 1_000, 0, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, 0, 1_000, 86_400_001, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, 0, 1_000, 1, 999, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, 0, 1_000, 1, 1_001, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(1_000, 0, 1_000, 1, 1_000, -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new TokenBucketParameters(100_000_000, 0, 1_000, 86_400_000, 1_000, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("30 days");
    assertThatThrownBy(() -> TokenBucketParameters.ofTokens(Long.MAX_VALUE, 0, 1, 1, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("scaling overflow");
    assertThatThrownBy(() -> RedisTokenBucketArithmetic.decide(parameters, null, -1))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(
            () ->
                RedisTokenBucketArithmetic.decide(
                    parameters, new TokenBucketState(10_000, 0, 1), 0))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(() -> new TokenBucketState(-1, 0, 0))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(() -> new TokenBucketState(0, -1, 0))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(() -> new TokenBucketState(0, 0, -1))
        .isInstanceOf(RedisStateException.class);
    assertThatThrownBy(() -> new TokenBucketTransition(true, null, 0, 0, false))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () -> new TokenBucketTransition(true, new TokenBucketState(0, 0, 0), -1, 0, false))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new TokenBucketTransition(true, new TokenBucketState(0, 0, 0), 0, -1, false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static TokenBucketParameters parameters(
      long capacity,
      long initialTokens,
      long refillTokens,
      long refillPeriodMilliseconds,
      long requestCost,
      long activationMilliseconds) {
    return TokenBucketParameters.ofTokens(
        capacity,
        initialTokens,
        refillTokens,
        refillPeriodMilliseconds,
        requestCost,
        activationMilliseconds);
  }
}
