package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import lab.ratelimiter.gateway.policy.persistence.OutboxEvent;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class PolicyOutboxDispatcherTest {

  private static final Instant NOW = Instant.parse("2026-08-03T12:00:00Z");
  private static final UUID EVENT_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

  @Mock private PostgresPolicyRepository repository;
  @Mock private PolicyEventPublisher publisher;

  private PolicyOutboxDispatcher dispatcher;
  private OutboxEvent event;

  @BeforeEach
  void setUp() {
    event = new OutboxEvent(EVENT_ID, 1, "POLICY_ACTIVATED", "catalog", 2, 3, NOW, 0);
    dispatcher =
        new PolicyOutboxDispatcher(
            repository,
            publisher,
            new PolicyEventCodec(4096),
            "gateway-1",
            Duration.ofSeconds(10),
            Clock.fixed(NOW, ZoneOffset.UTC));
  }

  @Test
  void claimedEventPublishesThenAcknowledges() {
    when(repository.claimOutbox("gateway-1", NOW, Duration.ofSeconds(10), 10))
        .thenReturn(Mono.just(List.of(event)));
    when(publisher.publish(org.mockito.ArgumentMatchers.anyString())).thenReturn(Mono.just(2L));
    when(repository.markOutboxPublished(EVENT_ID, NOW)).thenReturn(Mono.empty());

    OutboxDispatchResult result = dispatcher.dispatchOnce().block();

    assertThat(result).isEqualTo(new OutboxDispatchResult(1, 1, 0));
    verify(repository).markOutboxPublished(EVENT_ID, NOW);
  }

  @Test
  void publicationFailureRetainsEventForBoundedRetry() {
    when(repository.claimOutbox("gateway-1", NOW, Duration.ofSeconds(10), 10))
        .thenReturn(Mono.just(List.of(event)));
    when(publisher.publish(org.mockito.ArgumentMatchers.anyString()))
        .thenReturn(Mono.error(new IllegalStateException("redis unavailable")));
    when(repository.markOutboxFailed(EVENT_ID, NOW.plusSeconds(1), "REDIS_UNAVAILABLE"))
        .thenReturn(Mono.empty());

    OutboxDispatchResult result = dispatcher.dispatchOnce().block();

    assertThat(result).isEqualTo(new OutboxDispatchResult(1, 0, 1));
    verify(repository).markOutboxFailed(EVENT_ID, NOW.plusSeconds(1), "REDIS_UNAVAILABLE");
  }
}
