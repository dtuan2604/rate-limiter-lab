package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

class SlidingWindowCounterApproximationTest {

  @Test
  void fixedWindowBoundaryBurstIsRestrictedByWeightedHistory() {
    SlidingCounterParameters sliding = new SlidingCounterParameters(5, 10_000, 1);
    SlidingCounterState previousWindowFull = new SlidingCounterState(0, 5, 0);

    SlidingCounterTransition atBoundary =
        RedisSlidingWindowCounterArithmetic.decide(sliding, previousWindowFull, 10_000);

    assertThat(atBoundary.allowed()).isFalse();
    assertThat(atBoundary.state().previousCount()).isEqualTo(5);
    assertThat(atBoundary.weightedNumerator()).isEqualTo(50_000);
    assertThat(fixedWindowAdmissionsAcrossBoundary(5, 5, 5)).isEqualTo(10);
  }

  @Test
  void deterministicOracleCasesShowBothOverestimationAndUnderestimation() {
    long window = 10_000;
    List<Long> early = List.of(0L, 0L, 0L, 0L, 0L);
    List<Long> late = List.of(9_999L, 9_999L, 9_999L, 9_999L, 9_999L);

    assertThat(errorNumerator(early, 11_000, window)).isEqualTo(45_000);
    assertThat(errorNumerator(late, 11_000, window)).isEqualTo(-5_000);
  }

  @Test
  void seededGeneratedTracesRecordObservedErrorWithoutClaimingAUniversalBound() {
    Random random = new Random(0x5C1D1A6L);
    long maximumOverestimateNumerator = 0;
    long maximumUnderestimateNumerator = 0;
    for (int trace = 0; trace < 10_000; trace++) {
      int eventCount = random.nextInt(21);
      List<Long> timestamps = new ArrayList<>();
      for (int event = 0; event < eventCount; event++) {
        timestamps.add((long) random.nextInt(1_000));
      }
      long observation = 1_000L + random.nextInt(1_000);
      long error = errorNumerator(timestamps, observation, 1_000);
      maximumOverestimateNumerator = Math.max(maximumOverestimateNumerator, error);
      maximumUnderestimateNumerator = Math.max(maximumUnderestimateNumerator, -error);
    }

    System.out.printf(
        "seeded sliding-counter observed error numerators: over=%d under=%d%n",
        maximumOverestimateNumerator, maximumUnderestimateNumerator);
    assertThat(maximumOverestimateNumerator).isPositive();
    assertThat(maximumUnderestimateNumerator).isPositive();
  }

  private static long errorNumerator(List<Long> timestamps, long now, long window) {
    long currentWindowStart = now / window * window;
    long elapsed = now - currentWindowStart;
    long previousStart = currentWindowStart - window;
    long previousCount =
        timestamps.stream()
            .filter(timestamp -> timestamp >= previousStart && timestamp < currentWindowStart)
            .count();
    long currentCount =
        timestamps.stream()
            .filter(timestamp -> timestamp >= currentWindowStart && timestamp <= now)
            .count();
    long exactCount =
        timestamps.stream()
            .filter(timestamp -> timestamp > now - window && timestamp <= now)
            .count();
    long counterNumerator = currentCount * window + previousCount * (window - elapsed);
    return counterNumerator - exactCount * window;
  }

  private static long fixedWindowAdmissionsAcrossBoundary(
      long limit, long beforeBoundary, long afterBoundary) {
    return Math.min(limit, beforeBoundary) + Math.min(limit, afterBoundary);
  }
}
