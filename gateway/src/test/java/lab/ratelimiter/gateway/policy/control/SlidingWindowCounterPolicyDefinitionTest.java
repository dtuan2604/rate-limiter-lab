package lab.ratelimiter.gateway.policy.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lab.ratelimiter.gateway.application.FailureMode;
import org.junit.jupiter.api.Test;

class SlidingWindowCounterPolicyDefinitionTest {

  @Test
  void preservesTypedConfigurationAndExactWindowLiteral() {
    WindowDuration window = WindowDuration.parse("60s");
    SlidingWindowCounterAlgorithmDefinition algorithm =
        new SlidingWindowCounterAlgorithmDefinition(100, window, 3);

    assertThat(definition(algorithm).algorithm()).isSameAs(algorithm);
    assertThat(algorithm.type()).isEqualTo(PolicyAlgorithmType.SLIDING_WINDOW_COUNTER);
    assertThat(window.amount()).isEqualTo(60);
    assertThat(window.unit()).isEqualTo(WindowDuration.Unit.SECONDS);
    assertThat(window.toMilliseconds()).isEqualTo(60_000);
    assertThat(window.toString()).isEqualTo("60s");
  }

  @Test
  void acceptsDocumentedSafeBoundaries() {
    definition(new SlidingWindowCounterAlgorithmDefinition(1, WindowDuration.parse("1ms"), 1));
    definition(
        new SlidingWindowCounterAlgorithmDefinition(
            1_000_000, WindowDuration.parse("1d"), 1_000_000));
  }

  @Test
  void rejectsInvalidLimitWindowCostAndUnsafeBounds() {
    assertInvalid(() -> algorithm(0, "1s", 1));
    assertInvalid(() -> algorithm(1_000_001, "1s", 1));
    assertInvalid(() -> algorithm(10, "0s", 1));
    assertInvalid(() -> algorithm(10, "1.5s", 1));
    assertInvalid(() -> algorithm(10, "86400001ms", 1));
    assertInvalid(() -> algorithm(10, "1s", 0));
    assertInvalid(() -> algorithm(10, "1s", 11));
    assertInvalid(() -> new SlidingWindowCounterAlgorithmDefinition(10, null, 1));
  }

  @Test
  void rejectsNonCanonicalOrOverflowingWindowLiterals() {
    for (String value : List.of("", "01s", "+1s", "1S", "1 s", "1", "9223372036854775808ms")) {
      assertInvalid(() -> WindowDuration.parse(value));
    }
    assertInvalid(() -> WindowDuration.parse(null));
  }

  private static SlidingWindowCounterAlgorithmDefinition algorithm(
      long limit, String window, long requestCost) {
    return new SlidingWindowCounterAlgorithmDefinition(
        limit, WindowDuration.parse(window), requestCost);
  }

  private static PolicyDefinition definition(PolicyAlgorithmDefinition algorithm) {
    return new PolicyDefinition(
        "Sliding counter policy",
        "catalog.items",
        "/proxy/catalog/items",
        List.of("GET"),
        List.of(
            new PolicyIdentityComponent("HEADER", "X-Client-Id"),
            new PolicyIdentityComponent("ROUTE", null)),
        algorithm,
        FailureMode.FAIL_CLOSED,
        100);
  }

  private static void assertInvalid(Runnable operation) {
    assertThatThrownBy(operation::run)
        .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
  }
}
