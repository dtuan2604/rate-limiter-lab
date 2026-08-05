package lab.ratelimiter.gateway.policy.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import lab.ratelimiter.gateway.application.FailureMode;
import org.junit.jupiter.api.Test;

class TokenBucketPolicyDefinitionTest {

  @Test
  void preservesTypedConfigurationAndExactRefillPeriodLiteral() {
    RefillPeriod period = RefillPeriod.parse("1s");
    TokenBucketAlgorithmDefinition algorithm =
        new TokenBucketAlgorithmDefinition(10, 4, 2, period, 3);
    PolicyDefinition definition = definition(algorithm);

    assertThat(definition.algorithm()).isSameAs(algorithm);
    assertThat(period.amount()).isEqualTo(1);
    assertThat(period.unit()).isEqualTo(RefillPeriod.Unit.SECONDS);
    assertThat(period.toMilliseconds()).isEqualTo(1_000);
    assertThat(period.toString()).isEqualTo("1s");
  }

  @Test
  void acceptsDocumentedBoundaries() {
    definition(new TokenBucketAlgorithmDefinition(1, 0, 1, RefillPeriod.parse("1ms"), 1));
    definition(
        new TokenBucketAlgorithmDefinition(
            100_000, 100_000, 100_000, RefillPeriod.parse("1d"), 100_000));
  }

  @Test
  void rejectsEveryInvalidFieldAndUnsafeEmptyToFullInterval() {
    assertInvalid(() -> algorithm(0, 0, 1, "1s", 1));
    assertInvalid(() -> algorithm(100_001, 0, 1, "1s", 1));
    assertInvalid(() -> algorithm(10, -1, 1, "1s", 1));
    assertInvalid(() -> algorithm(10, 11, 1, "1s", 1));
    assertInvalid(() -> algorithm(10, 0, 0, "1s", 1));
    assertInvalid(() -> algorithm(10, 0, 100_001, "1s", 1));
    assertInvalid(() -> algorithm(10, 0, 1, "0s", 1));
    assertInvalid(() -> algorithm(10, 0, 1, "1.5s", 1));
    assertInvalid(() -> algorithm(10, 0, 1, "86400001ms", 1));
    assertInvalid(() -> algorithm(10, 0, 1, "1s", 0));
    assertInvalid(() -> algorithm(10, 0, 1, "1s", 11));
    assertInvalid(() -> algorithm(100_000, 0, 1, "1d", 1));
  }

  @Test
  void rejectsNonCanonicalOrOverflowingRefillPeriods() {
    for (String value : List.of("", "01s", "+1s", "1S", "1 s", "1", "9223372036854775808ms")) {
      assertInvalid(() -> RefillPeriod.parse(value));
    }
    assertInvalid(() -> RefillPeriod.parse(null));
  }

  private static TokenBucketAlgorithmDefinition algorithm(
      long capacity, long initialTokens, long refillTokens, String refillPeriod, long requestCost) {
    return new TokenBucketAlgorithmDefinition(
        capacity, initialTokens, refillTokens, RefillPeriod.parse(refillPeriod), requestCost);
  }

  private static PolicyDefinition definition(PolicyAlgorithmDefinition algorithm) {
    return new PolicyDefinition(
        "Token Bucket policy",
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
