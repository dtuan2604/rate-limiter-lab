package lab.ratelimiter.gateway.policy;

import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Mono;

public final class PolicyEventConsumer {

  private final PolicyEventCodec codec;
  private final PolicySnapshotRefreshCoordinator coordinator;
  private final AtomicLong lastRevision = new AtomicLong();
  private final AtomicReference<ProcessedPolicyEvent> lastProcessed = new AtomicReference<>();
  private final AtomicBoolean paused = new AtomicBoolean();
  private final Clock clock;

  public PolicyEventConsumer(PolicyEventCodec codec, PolicySnapshotRefreshCoordinator coordinator) {
    this(codec, coordinator, Clock.systemUTC());
  }

  public PolicyEventConsumer(
      PolicyEventCodec codec, PolicySnapshotRefreshCoordinator coordinator, Clock clock) {
    this.codec = Objects.requireNonNull(codec, "codec");
    this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Mono<PolicyEventOutcome> process(String payload) {
    if (paused.get()) {
      return Mono.just(PolicyEventOutcome.PAUSED);
    }
    PolicyInvalidationEvent event;
    try {
      event = codec.decode(payload);
    } catch (IllegalArgumentException exception) {
      PolicyControlLogger.eventRejected("MALFORMED_OR_UNSUPPORTED");
      return Mono.just(PolicyEventOutcome.REJECTED);
    }
    if (event.policySetRevision() <= lastRevision.get()) {
      return Mono.just(PolicyEventOutcome.IGNORED);
    }
    return coordinator
        .refresh(event.policySetRevision(), ReloadTrigger.POLICY_EVENT)
        .map(
            result -> {
              lastRevision.accumulateAndGet(event.policySetRevision(), Math::max);
              lastProcessed.set(new ProcessedPolicyEvent(event, result.outcome(), clock.instant()));
              PolicyControlLogger.eventProcessed(
                  event,
                  result,
                  coordinator.installedPolicyVersion(event.policyId()),
                  java.time.Duration.between(event.occurredAt(), clock.instant()));
              return PolicyEventOutcome.REFRESHED;
            })
        .doOnError(ignored -> PolicyControlLogger.eventProcessingFailed(event));
  }

  public void pause() {
    paused.set(true);
  }

  public void resume() {
    paused.set(false);
  }

  public boolean paused() {
    return paused.get();
  }

  public ProcessedPolicyEvent lastProcessedEvent() {
    return lastProcessed.get();
  }
}
