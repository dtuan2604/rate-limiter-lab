package lab.ratelimiter.gateway.policy;

import java.time.Duration;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public final class PolicyControlScheduler implements SmartLifecycle {

  private final PolicyOutboxDispatcher dispatcher;
  private final PolicyReconciler reconciler;
  private final Duration outboxInterval;
  private final Duration reconciliationInterval;
  private final Duration reconciliationInitialDelay;
  private volatile Disposable outboxSchedule;
  private volatile Disposable reconciliationSchedule;
  private volatile boolean running;

  public PolicyControlScheduler(
      PolicyOutboxDispatcher dispatcher,
      PolicyReconciler reconciler,
      Duration outboxInterval,
      Duration reconciliationInterval) {
    this(dispatcher, reconciler, outboxInterval, reconciliationInterval, reconciliationInterval);
  }

  public PolicyControlScheduler(
      PolicyOutboxDispatcher dispatcher,
      PolicyReconciler reconciler,
      Duration outboxInterval,
      Duration reconciliationInterval,
      Duration reconciliationInitialDelay) {
    this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    this.reconciler = Objects.requireNonNull(reconciler, "reconciler");
    this.outboxInterval = Objects.requireNonNull(outboxInterval, "outboxInterval");
    this.reconciliationInterval =
        Objects.requireNonNull(reconciliationInterval, "reconciliationInterval");
    this.reconciliationInitialDelay =
        Objects.requireNonNull(reconciliationInitialDelay, "reconciliationInitialDelay");
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    running = true;
    outboxSchedule =
        Flux.interval(Duration.ZERO, outboxInterval)
            .concatMap(ignored -> dispatcher.dispatchOnce().onErrorResume(error -> Mono.empty()))
            .subscribe();
    reconciliationSchedule =
        Mono.delay(reconciliationInitialDelay)
            .then(
                Mono.defer(reconciler::reconcile)
                    .retryWhen(
                        Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                            .maxBackoff(Duration.ofMinutes(5))
                            .jitter(0.2)))
            .repeatWhen(completed -> completed.delayElements(reconciliationInterval))
            .subscribe();
  }

  @Override
  public void stop() {
    dispose(outboxSchedule);
    dispose(reconciliationSchedule);
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 50;
  }

  private static void dispose(Disposable disposable) {
    if (disposable != null) {
      disposable.dispose();
    }
  }

  public static Duration boundedInitialDelay(Duration interval, String instanceId) {
    Objects.requireNonNull(interval, "interval");
    Objects.requireNonNull(instanceId, "instanceId");
    long maximumJitterMilliseconds = Math.max(1, interval.toMillis() / 10);
    long jitterMilliseconds = Math.floorMod(instanceId.hashCode(), maximumJitterMilliseconds + 1);
    return interval.plusMillis(jitterMilliseconds);
  }
}
