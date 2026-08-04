package lab.ratelimiter.gateway.policy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import lab.ratelimiter.gateway.policy.persistence.OutboxEvent;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import reactor.core.publisher.Mono;

public final class PolicyOutboxDispatcher {

  private static final int CLAIM_LIMIT = 10;
  private final PostgresPolicyRepository repository;
  private final PolicyEventPublisher publisher;
  private final PolicyEventCodec codec;
  private final String workerId;
  private final Duration leaseDuration;
  private final Clock clock;

  public PolicyOutboxDispatcher(
      PostgresPolicyRepository repository,
      PolicyEventPublisher publisher,
      PolicyEventCodec codec,
      String workerId,
      Duration leaseDuration,
      Clock clock) {
    this.repository = Objects.requireNonNull(repository, "repository");
    this.publisher = Objects.requireNonNull(publisher, "publisher");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.workerId = Objects.requireNonNull(workerId, "workerId");
    this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public Mono<OutboxDispatchResult> dispatchOnce() {
    Instant now = clock.instant();
    return repository
        .claimOutbox(workerId, now, leaseDuration, CLAIM_LIMIT)
        .flatMapMany(events -> reactor.core.publisher.Flux.fromIterable(events))
        .concatMap(event -> publish(event, now))
        .collectList()
        .map(
            outcomes -> {
              int published = (int) outcomes.stream().filter(Boolean::booleanValue).count();
              return new OutboxDispatchResult(
                  outcomes.size(), published, outcomes.size() - published);
            });
  }

  private Mono<Boolean> publish(OutboxEvent event, Instant now) {
    PolicyInvalidationEvent payload =
        new PolicyInvalidationEvent(
            event.eventVersion(),
            event.eventType(),
            event.policyId(),
            event.version(),
            event.policySetRevision(),
            event.eventId(),
            event.occurredAt());
    return publisher
        .publish(codec.encode(payload))
        .then(Mono.defer(() -> repository.markOutboxPublished(event.eventId(), now)))
        .then(
            Mono.fromSupplier(
                () -> {
                  PolicyControlLogger.publication(event, "PUBLISHED");
                  return true;
                }))
        .onErrorResume(
            ignored ->
                Mono.defer(
                        () ->
                            repository.markOutboxFailed(
                                event.eventId(),
                                now.plus(retryDelay(event.attemptCount())),
                                "REDIS_UNAVAILABLE"))
                    .then(
                        Mono.fromSupplier(
                            () -> {
                              PolicyControlLogger.publication(event, "RETRY_SCHEDULED");
                              return false;
                            })));
  }

  private static Duration retryDelay(int attemptCount) {
    long seconds = 1L << Math.min(Math.max(attemptCount, 0), 8);
    return Duration.ofSeconds(Math.min(seconds, 300));
  }
}
