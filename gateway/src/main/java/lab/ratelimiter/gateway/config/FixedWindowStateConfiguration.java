package lab.ratelimiter.gateway.config;

import java.time.Clock;
import java.util.List;
import lab.ratelimiter.gateway.application.FixedWindowStateAdapter;
import lab.ratelimiter.gateway.application.InMemoryFixedWindowStateAdapter;
import lab.ratelimiter.gateway.state.redis.RedisFixedWindowStateAdapter;
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
      havingValue = "REDIS",
      matchIfMissing = true)
  FixedWindowStateAdapter redisFixedWindowStateAdapter(
      ReactiveStringRedisTemplate redis,
      RedisScript<List<?>> fixedWindowScript,
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
    return createScript();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RedisScript<List<?>> createScript() {
    return (RedisScript)
        RedisScript.of(new ClassPathResource("redis/fixed-window-v1.lua"), List.class);
  }
}
