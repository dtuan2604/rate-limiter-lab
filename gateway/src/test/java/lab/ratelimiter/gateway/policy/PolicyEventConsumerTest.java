package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class PolicyEventConsumerTest {

  @Mock private PolicySnapshotRefreshCoordinator coordinator;

  private PolicyEventCodec codec;
  private PolicyEventConsumer consumer;

  @BeforeEach
  void setUp() {
    codec = new PolicyEventCodec(4096);
    consumer = new PolicyEventConsumer(codec, coordinator);
  }

  @Test
  void validNewerEventRefreshesFromPostgresAndDuplicateOrOlderEventsAreIgnored() {
    String revisionThree = event(3, 2);
    when(coordinator.refresh(3, ReloadTrigger.POLICY_EVENT))
        .thenReturn(
            Mono.just(
                new PolicyRefreshResult(
                    ReloadTrigger.POLICY_EVENT, ReloadOutcome.INSTALLED, 3, 3, 3)));

    assertThat(consumer.process(revisionThree).block()).isEqualTo(PolicyEventOutcome.REFRESHED);
    assertThat(consumer.process(revisionThree).block()).isEqualTo(PolicyEventOutcome.IGNORED);
    assertThat(consumer.process(event(2, 1)).block()).isEqualTo(PolicyEventOutcome.IGNORED);

    verify(coordinator).refresh(3, ReloadTrigger.POLICY_EVENT);
    assertThat(consumer.lastProcessedEvent().event().policySetRevision()).isEqualTo(3);
  }

  @Test
  void malformedUnknownOversizedAndPausedEventsLeaveSnapshotUntouched() {
    assertThat(consumer.process("not-json").block()).isEqualTo(PolicyEventOutcome.REJECTED);
    assertThat(consumer.process(eventWith("\"eventVersion\":2")).block())
        .isEqualTo(PolicyEventOutcome.REJECTED);
    assertThat(consumer.process(eventWith("\"eventType\":\"UNKNOWN\"")).block())
        .isEqualTo(PolicyEventOutcome.REJECTED);
    assertThat(consumer.process("x".repeat(4097)).block()).isEqualTo(PolicyEventOutcome.REJECTED);
    consumer.pause();
    assertThat(consumer.process(event(4, 4)).block()).isEqualTo(PolicyEventOutcome.PAUSED);

    verify(coordinator, never())
        .refresh(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void refreshFailureDoesNotAdvanceLastProcessedRevisionAndCanBeRetried() {
    String event = event(5, 5);
    when(coordinator.refresh(5, ReloadTrigger.POLICY_EVENT))
        .thenReturn(Mono.error(new IllegalStateException("database unavailable")))
        .thenReturn(
            Mono.just(
                new PolicyRefreshResult(
                    ReloadTrigger.POLICY_EVENT, ReloadOutcome.INSTALLED, 5, 5, 5)));

    assertThat(consumer.process(event).onErrorComplete().block()).isNull();
    assertThat(consumer.lastProcessedEvent()).isNull();
    assertThat(consumer.process(event).block()).isEqualTo(PolicyEventOutcome.REFRESHED);
  }

  private String event(long revision, long version) {
    return codec.encode(
        new PolicyInvalidationEvent(
            1,
            "POLICY_ACTIVATED",
            "catalog",
            version,
            revision,
            UUID.randomUUID(),
            Instant.parse("2026-08-03T12:00:00Z")));
  }

  private static String eventWith(String replacement) {
    String valid =
        "{\"eventVersion\":1,\"eventType\":\"POLICY_ACTIVATED\",\"policyId\":\"catalog\","
            + "\"version\":1,\"policySetRevision\":1,"
            + "\"eventId\":\"11111111-1111-1111-1111-111111111111\","
            + "\"occurredAt\":\"2026-08-03T12:00:00Z\"}";
    if (replacement.contains("eventVersion")) {
      return valid.replace("\"eventVersion\":1", replacement);
    }
    return valid.replace("\"eventType\":\"POLICY_ACTIVATED\"", replacement);
  }
}
