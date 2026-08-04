package lab.ratelimiter.gateway.policy;

import java.time.Duration;
import java.util.Objects;
import org.springframework.context.SmartLifecycle;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;

public final class RedisPolicyEventSubscriber implements SmartLifecycle {

  private final ReactiveRedisMessageListenerContainer container;
  private final String channel;
  private final PolicyEventConsumer consumer;
  private final PolicyPropagationStatus propagationStatus;
  private volatile Disposable subscription;
  private volatile boolean running;

  public RedisPolicyEventSubscriber(
      ReactiveRedisMessageListenerContainer container,
      String channel,
      PolicyEventConsumer consumer) {
    this(container, channel, consumer, new PolicyPropagationStatus());
  }

  public RedisPolicyEventSubscriber(
      ReactiveRedisMessageListenerContainer container,
      String channel,
      PolicyEventConsumer consumer,
      PolicyPropagationStatus propagationStatus) {
    this.container = Objects.requireNonNull(container, "container");
    this.channel = Objects.requireNonNull(channel, "channel");
    this.consumer = Objects.requireNonNull(consumer, "consumer");
    this.propagationStatus = Objects.requireNonNull(propagationStatus, "propagationStatus");
  }

  @Override
  public void start() {
    if (running) {
      return;
    }
    running = true;
    subscription =
        container
            .receive(ChannelTopic.of(channel))
            .doOnSubscribe(
                ignored -> {
                  running = true;
                  propagationStatus.markEventSubscriptionAvailable();
                  PolicyControlLogger.subscription("AVAILABLE");
                })
            .concatMap(
                message ->
                    consumer.process(message.getMessage()).onErrorResume(ignored -> Mono.empty()))
            .doOnError(
                ignored -> {
                  running = false;
                  propagationStatus.markEventSubscriptionUnavailable();
                  PolicyControlLogger.subscription("UNAVAILABLE");
                })
            .retryWhen(
                Retry.backoff(Long.MAX_VALUE, Duration.ofSeconds(1))
                    .maxBackoff(Duration.ofMinutes(5)))
            .subscribe();
  }

  @Override
  public void stop() {
    Disposable current = subscription;
    if (current != null) {
      current.dispose();
    }
    propagationStatus.markEventSubscriptionUnavailable();
    running = false;
  }

  @Override
  public boolean isRunning() {
    return running;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE - 100;
  }
}
