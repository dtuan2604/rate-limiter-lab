package lab.ratelimiter.gateway.policy;

import java.util.Objects;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import reactor.core.publisher.Mono;

public final class RedisPolicyEventPublisher implements PolicyEventPublisher {

  private final ReactiveStringRedisTemplate redis;
  private final String channel;

  public RedisPolicyEventPublisher(ReactiveStringRedisTemplate redis, String channel) {
    this.redis = Objects.requireNonNull(redis, "redis");
    this.channel = Objects.requireNonNull(channel, "channel");
  }

  @Override
  public Mono<Long> publish(String payload) {
    return redis.convertAndSend(channel, payload);
  }
}
