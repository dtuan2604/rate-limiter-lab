package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.policy.control.ActivePolicySet;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyIdentityComponent;
import lab.ratelimiter.gateway.policy.control.PolicyLifecycle;
import lab.ratelimiter.gateway.policy.control.RefillPeriod;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;
import lab.ratelimiter.gateway.policy.control.TokenBucketAlgorithmDefinition;
import org.junit.jupiter.api.Test;

class PolicySnapshotCompilerTokenBucketTest {

  private static final Instant ACTIVATED = Instant.parse("2026-08-04T12:00:00Z");
  private final PolicySnapshotCompiler compiler = new PolicySnapshotCompiler();

  @Test
  void compilesTokenBucketIntoTypedRuntimeAlgorithmWithDatabaseActivationAnchor() {
    PolicySnapshot snapshot =
        compiler.compile(
            new ActivePolicySet(7, List.of(stored(ACTIVATED))),
            Clock.fixed(ACTIVATED.plusSeconds(1), ZoneOffset.UTC));

    CompiledPolicy policy = snapshot.match("GET", "/proxy/catalog/items").orElseThrow();
    assertThat(policy.compiledAlgorithm())
        .isInstanceOfSatisfying(
            CompiledTokenBucketAlgorithm.class,
            token -> {
              assertThat(token.policy().capacity()).isEqualTo(10);
              assertThat(token.policy().initialTokens()).isEqualTo(4);
              assertThat(token.policy().refillTokens()).isEqualTo(2);
              assertThat(token.policy().refillPeriod().toMillis()).isEqualTo(1_000);
              assertThat(token.requestCost()).isEqualTo(3);
              assertThat(token.activationTime()).isEqualTo(ACTIVATED);
            });
  }

  @Test
  void rejectsTokenBucketWithoutDatabaseActivationAnchor() {
    assertThatThrownBy(
            () ->
                compiler.compile(
                    new ActivePolicySet(7, List.of(stored(null))),
                    Clock.fixed(ACTIVATED, ZoneOffset.UTC)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("activation");
  }

  private static StoredPolicyVersion stored(Instant activatedAt) {
    return new StoredPolicyVersion(
        "catalog-token",
        "Catalog token bucket",
        2,
        PolicyLifecycle.ACTIVE,
        7,
        new PolicyDefinition(
            null,
            "catalog.items",
            "/proxy/catalog/items",
            List.of("GET"),
            List.of(
                new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                new PolicyIdentityComponent("ROUTE", null)),
            new TokenBucketAlgorithmDefinition(10, 4, 2, RefillPeriod.parse("1s"), 3),
            FailureMode.FAIL_CLOSED,
            100),
        ACTIVATED.minusSeconds(30),
        "admin",
        activatedAt,
        activatedAt == null ? null : "admin");
  }
}
