package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import org.junit.jupiter.api.Test;

class PolicySnapshotTest {

  @Test
  void immutableSnapshotMatchesByExactMethodPathAndPriority() {
    PolicySnapshot snapshot =
        new PolicySnapshot(
            4,
            Instant.parse("2026-08-03T12:00:00Z"),
            List.of(compiled("lower", 1, 10), compiled("higher", 2, 100)));

    assertThat(snapshot.match("GET", "/proxy/catalog/items"))
        .get()
        .extracting(policy -> policy.policy().policyId().value())
        .isEqualTo("higher");
    assertThat(snapshot.match("POST", "/proxy/catalog/items")).isEmpty();
    assertThat(snapshot.activeVersions())
        .containsExactlyInAnyOrderEntriesOf(java.util.Map.of("higher", 2L, "lower", 1L));
  }

  @Test
  void storeNeverRegressesAndConcurrentReadersSeeOnlyWholeSnapshots() throws Exception {
    PolicySnapshot versionOne =
        new PolicySnapshot(
            1, Instant.parse("2026-08-03T12:00:00Z"), List.of(compiled("catalog", 1, 100)));
    PolicySnapshot versionTwo =
        new PolicySnapshot(
            2, Instant.parse("2026-08-03T12:01:00Z"), List.of(compiled("catalog", 2, 100)));
    PolicySnapshotStore store = new PolicySnapshotStore(versionOne);
    AtomicBoolean invalidRead = new AtomicBoolean();
    CountDownLatch started = new CountDownLatch(8);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      for (int index = 0; index < 8; index++) {
        executor.submit(
            () -> {
              started.countDown();
              for (int read = 0; read < 10_000; read++) {
                PolicySnapshot captured = store.current();
                long revision = captured.revision();
                long version =
                    captured
                        .match("GET", "/proxy/catalog/items")
                        .orElseThrow()
                        .policy()
                        .policyVersion()
                        .value();
                if (!((revision == 1 && version == 1) || (revision == 2 && version == 2))) {
                  invalidRead.set(true);
                }
              }
            });
      }
      started.await();
      assertThat(store.install(versionTwo)).isTrue();
    }

    assertThat(invalidRead).isFalse();
    assertThat(store.install(versionOne)).isFalse();
    assertThat(store.current()).isSameAs(versionTwo);
  }

  @Test
  void rejectsInvalidSnapshotMetadataDuplicatePoliciesAndInvalidPriorities() {
    assertThatThrownBy(() -> new PolicySnapshot(-1, Instant.EPOCH, List.of()))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                new PolicySnapshot(
                    1, Instant.EPOCH, List.of(compiled("same", 1, 1), compiled("same", 2, 2))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("duplicate");
    assertThatThrownBy(() -> compiled("low", 1, -1)).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> compiled("high", 1, 1001))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullRouteLookupComponents() {
    PolicySnapshot snapshot =
        new PolicySnapshot(0, Instant.EPOCH, List.of(compiled("catalog", 1, 100)));

    assertThatThrownBy(() -> snapshot.match(null, "/proxy/catalog/items"))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> snapshot.match("GET", null)).isInstanceOf(NullPointerException.class);
  }

  private static CompiledPolicy compiled(String id, long version, int priority) {
    return new CompiledPolicy(
        "catalog.items",
        "/proxy/catalog/items",
        "GET",
        new FixedWindowPolicy(
            new PolicyId(id), new PolicyVersion(version), 5, Duration.ofSeconds(10)),
        FailureMode.FAIL_CLOSED,
        priority);
  }
}
