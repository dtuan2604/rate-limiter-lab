package lab.ratelimiter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import lab.ratelimiter.gateway.application.FixedWindowStateAdapter;
import lab.ratelimiter.gateway.application.InMemoryFixedWindowStateAdapter;
import lab.ratelimiter.gateway.state.redis.RedisFixedWindowStateAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;

class FixedWindowStateConfigurationTest {

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner()
          .withUserConfiguration(FixedWindowStateConfiguration.class, Dependencies.class)
          .withPropertyValues("rate-limiter.gateway.redis-command-timeout=750ms");

  @Test
  void selectsRedisAdapterForDistributedMode() {
    contextRunner
        .withPropertyValues("rate-limiter.gateway.state-backend=REDIS")
        .run(
            context -> {
              assertThat(context).hasSingleBean(FixedWindowStateAdapter.class);
              assertThat(context.getBean(FixedWindowStateAdapter.class))
                  .isInstanceOf(RedisFixedWindowStateAdapter.class);
            });
  }

  @Test
  void selectsInMemoryAdapterOnlyWhenExplicitlyConfigured() {
    contextRunner
        .withPropertyValues("rate-limiter.gateway.state-backend=IN_MEMORY")
        .run(
            context -> {
              assertThat(context).hasSingleBean(FixedWindowStateAdapter.class);
              assertThat(context.getBean(FixedWindowStateAdapter.class))
                  .isInstanceOf(InMemoryFixedWindowStateAdapter.class);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class Dependencies {

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }

    @Bean
    ReactiveStringRedisTemplate redis() {
      return mock(ReactiveStringRedisTemplate.class);
    }
  }
}
