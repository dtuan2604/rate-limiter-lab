package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.policy.control.ActivePolicySet;
import lab.ratelimiter.gateway.policy.control.PolicyDefinition;
import lab.ratelimiter.gateway.policy.control.PolicyIdentityComponent;
import lab.ratelimiter.gateway.policy.control.PolicyLifecycle;
import lab.ratelimiter.gateway.policy.control.SlidingWindowCounterAlgorithmDefinition;
import lab.ratelimiter.gateway.policy.control.StoredPolicyVersion;
import lab.ratelimiter.gateway.policy.control.WindowDuration;
import org.junit.jupiter.api.Test;

class PolicySnapshotCompilerSlidingCounterTest {

  @Test
  void compilesSlidingCounterIntoExplicitBoundedRuntimeAlgorithm() {
    Instant now = Instant.parse("2026-08-05T12:00:00Z");
    StoredPolicyVersion stored =
        new StoredPolicyVersion(
            "catalog-sliding",
            "Catalog sliding",
            3,
            PolicyLifecycle.ACTIVE,
            1,
            new PolicyDefinition(
                null,
                "catalog.items",
                "/proxy/catalog/items",
                List.of("GET"),
                List.of(
                    new PolicyIdentityComponent("HEADER", "X-Client-Id"),
                    new PolicyIdentityComponent("ROUTE", null)),
                new SlidingWindowCounterAlgorithmDefinition(100, WindowDuration.parse("60s"), 3),
                FailureMode.FAIL_CLOSED,
                100),
            now.minusSeconds(30),
            "admin",
            now,
            "admin");

    CompiledPolicy policy =
        new PolicySnapshotCompiler()
            .compile(new ActivePolicySet(7, List.of(stored)), Clock.fixed(now, ZoneOffset.UTC))
            .match("GET", "/proxy/catalog/items")
            .orElseThrow();

    assertThat(policy.compiledAlgorithm())
        .isInstanceOfSatisfying(
            CompiledSlidingWindowCounterAlgorithm.class,
            sliding -> {
              assertThat(sliding.policy().limit()).isEqualTo(100);
              assertThat(sliding.policy().window()).isEqualTo(java.time.Duration.ofSeconds(60));
              assertThat(sliding.requestCost()).isEqualTo(3);
            });
  }
}
