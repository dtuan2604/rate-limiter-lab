package lab.ratelimiter.gateway.config;

import java.time.Clock;
import java.util.List;
import lab.ratelimiter.gateway.application.FixedWindowStateAdapter;
import lab.ratelimiter.gateway.application.InMemoryFixedWindowStateAdapter;
import lab.ratelimiter.gateway.application.InMemorySlidingWindowCounterStateAdapter;
import lab.ratelimiter.gateway.application.InMemoryTokenBucketStateAdapter;
import lab.ratelimiter.gateway.application.SlidingWindowCounterStateAdapter;
import lab.ratelimiter.gateway.application.TokenBucketStateAdapter;
import lab.ratelimiter.gateway.state.redis.RedisFixedWindowStateAdapter;
import lab.ratelimiter.gateway.state.redis.RedisSlidingWindowCounterStateAdapter;
import lab.ratelimiter.gateway.state.redis.RedisTokenBucketStateAdapter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class FixedWindowStateConfiguration {

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "IN_MEMORY")
  FixedWindowStateAdapter inMemoryFixedWindowStateAdapter(Clock gatewayClock) {
    return new InMemoryFixedWindowStateAdapter(gatewayClock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "IN_MEMORY")
  TokenBucketStateAdapter inMemoryTokenBucketStateAdapter(Clock gatewayClock) {
    return new InMemoryTokenBucketStateAdapter(gatewayClock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "IN_MEMORY")
  SlidingWindowCounterStateAdapter inMemorySlidingWindowCounterStateAdapter(Clock gatewayClock) {
    return new InMemorySlidingWindowCounterStateAdapter(gatewayClock);
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "REDIS",
      matchIfMissing = true)
  FixedWindowStateAdapter redisFixedWindowStateAdapter(
      ReactiveStringRedisTemplate redis,
      @Qualifier("fixedWindowScript") RedisScript<List<?>> fixedWindowScript,
      GatewayProperties properties) {
    return new RedisFixedWindowStateAdapter(
        redis, fixedWindowScript, properties.redisCommandTimeout());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "REDIS",
      matchIfMissing = true)
  RedisScript<List<?>> fixedWindowScript() {
    return createScript("redis/fixed-window-v1.lua");
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "REDIS",
      matchIfMissing = true)
  TokenBucketStateAdapter redisTokenBucketStateAdapter(
      ReactiveStringRedisTemplate redis,
      @Qualifier("tokenBucketScript") RedisScript<List<?>> tokenBucketScript,
      GatewayProperties properties) {
    return new RedisTokenBucketStateAdapter(
        redis, tokenBucketScript, properties.redisCommandTimeout());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "REDIS",
      matchIfMissing = true)
  RedisScript<List<?>> tokenBucketScript() {
    return createScript("redis/token-bucket-v1.lua");
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "REDIS",
      matchIfMissing = true)
  SlidingWindowCounterStateAdapter redisSlidingWindowCounterStateAdapter(
      ReactiveStringRedisTemplate redis,
      @Qualifier("slidingWindowCounterScript") RedisScript<List<?>> slidingWindowCounterScript,
      GatewayProperties properties) {
    return new RedisSlidingWindowCounterStateAdapter(
        redis, slidingWindowCounterScript, properties.redisCommandTimeout());
  }

  @Bean
  @ConditionalOnProperty(
      prefix = "rate-limiter.gateway",
      name = "state-backend",
      havingValue = "REDIS",
      matchIfMissing = true)
  RedisScript<List<?>> slidingWindowCounterScript() {
    return createScript("redis/sliding-window-counter-v1.lua");
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RedisScript<List<?>> createScript(String resource) {
    return (RedisScript) RedisScript.of(new ClassPathResource(resource), List.class);
  }
}
