package lab.ratelimiter.gateway.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class PolicyControlLifecycleTest {

  @Mock private PolicyOutboxDispatcher dispatcher;
  @Mock private PolicyReconciler reconciler;
  @Mock private ReactiveRedisMessageListenerContainer listenerContainer;
  @Mock private PolicyEventConsumer consumer;

  @Test
  void schedulerStartIsIdempotentAndStopHandlesBothLifecycleStates() {
    when(dispatcher.dispatchOnce()).thenReturn(Mono.just(new OutboxDispatchResult(0, 0, 0)));
    var scheduler =
        new PolicyControlScheduler(dispatcher, reconciler, Duration.ofDays(1), Duration.ofDays(1));

    scheduler.stop();
    assertThat(scheduler.isRunning()).isFalse();
    scheduler.start();
    scheduler.start();
    assertThat(scheduler.isRunning()).isTrue();
    assertThat(scheduler.getPhase()).isEqualTo(Integer.MAX_VALUE - 50);
    scheduler.stop();
    assertThat(scheduler.isRunning()).isFalse();
  }

  @Test
  void reconciliationInitialDelayHasDeterministicBoundedPerReplicaJitter() {
    Duration interval = Duration.ofSeconds(30);

    Duration first = PolicyControlScheduler.boundedInitialDelay(interval, "gateway-1");
    Duration repeated = PolicyControlScheduler.boundedInitialDelay(interval, "gateway-1");

    assertThat(first).isEqualTo(repeated);
    assertThat(first).isBetween(interval, interval.plusSeconds(3));
  }

  @Test
  void redisSubscriberStartIsIdempotentAndStopHandlesBothLifecycleStates() {
    when(listenerContainer.receive(any(ChannelTopic.class))).thenReturn(Flux.never());
    PolicyPropagationStatus propagation = new PolicyPropagationStatus();
    var subscriber =
        new RedisPolicyEventSubscriber(listenerContainer, "policy-events", consumer, propagation);

    subscriber.stop();
    assertThat(subscriber.isRunning()).isFalse();
    assertThat(propagation.eventSubscriptionAvailable()).isFalse();
    subscriber.start();
    subscriber.start();
    assertThat(subscriber.isRunning()).isTrue();
    assertThat(propagation.eventSubscriptionAvailable()).isTrue();
    assertThat(subscriber.getPhase()).isEqualTo(Integer.MAX_VALUE - 100);
    subscriber.stop();
    assertThat(subscriber.isRunning()).isFalse();
    assertThat(propagation.eventSubscriptionAvailable()).isFalse();
  }
}
