package lab.ratelimiter.gateway.policy;

import reactor.core.publisher.Mono;

@FunctionalInterface
public interface PolicyEventPublisher {
  Mono<Long> publish(String payload);
}
