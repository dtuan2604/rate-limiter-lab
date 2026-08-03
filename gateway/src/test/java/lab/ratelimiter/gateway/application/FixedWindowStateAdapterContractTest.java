package lab.ratelimiter.gateway.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import lab.ratelimiter.gateway.state.redis.RedisFixedWindowStateAdapter;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class FixedWindowStateAdapterContractTest {

  private static final Instant IN_MEMORY_NOW = Instant.parse("2026-08-02T12:00:00Z");
  private static final FixedWindowPolicy POLICY =
      new FixedWindowPolicy(
          new PolicyId("shared-contract"), new PolicyVersion(1), 5, Duration.ofHours(1));

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static ReactiveStringRedisTemplate redis;
  private final ClientIdentityExtractor identities = new ClientIdentityExtractor();

  @BeforeAll
  static void connect() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redis = new ReactiveStringRedisTemplate(connectionFactory, RedisSerializationContext.string());
  }

  @AfterAll
  static void disconnect() {
    connectionFactory.destroy();
  }

  @BeforeEach
  void clearRedis() {
    redis.execute(connection -> connection.serverCommands().flushDb()).then().block();
  }

  @Test
  void inMemoryAndRedisShareCostOneLimitAndRejectionSemantics() {
    for (FixedWindowStateAdapter adapter : adapters()) {
      LimiterIdentity identity =
          identities.extract("contract-client", "catalog.items").orElseThrow();

      for (int request = 1; request <= 5; request++) {
        FixedWindowStateResult result = decide(adapter, identity);
        assertThat(result.decision().allowed()).isTrue();
        assertThat(result.currentCount()).isEqualTo(request);
        assertThat(result.decision().remaining()).isEqualTo(5 - request);
      }

      FixedWindowStateResult rejected = decide(adapter, identity);
      assertThat(rejected.decision().allowed()).isFalse();
      assertThat(rejected.currentCount()).isEqualTo(5);
      assertThat(rejected.decision().remaining()).isZero();
      assertThat(rejected.decision().retryAfter()).isPresent();
      assertThat(rejected.resetAfter()).isPositive().isLessThanOrEqualTo(POLICY.window());
      redis.execute(connection -> connection.serverCommands().flushDb()).then().block();
    }
  }

  @Test
  void inMemoryAndRedisKeepNormalizedIdentitiesIndependent() {
    for (FixedWindowStateAdapter adapter : adapters()) {
      LimiterIdentity first =
          identities.extract("contract-client-a", "catalog.items").orElseThrow();
      LimiterIdentity second =
          identities.extract("contract-client-b", "catalog.items").orElseThrow();

      for (int request = 0; request < 5; request++) {
        assertThat(decide(adapter, first).decision().allowed()).isTrue();
      }
      assertThat(decide(adapter, first).decision().allowed()).isFalse();
      assertThat(decide(adapter, second).decision().allowed()).isTrue();
      redis.execute(connection -> connection.serverCommands().flushDb()).then().block();
    }
  }

  private static List<FixedWindowStateAdapter> adapters() {
    return List.of(
        new InMemoryFixedWindowStateAdapter(Clock.fixed(IN_MEMORY_NOW, ZoneOffset.UTC)),
        new RedisFixedWindowStateAdapter(redis, fixedWindowScript(), Duration.ofSeconds(2)));
  }

  private static FixedWindowStateResult decide(
      FixedWindowStateAdapter adapter, LimiterIdentity identity) {
    return adapter.decide(POLICY, identity, new RateLimitRequest(1)).block();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RedisScript<List<?>> fixedWindowScript() {
    return (RedisScript)
        RedisScript.of(new ClassPathResource("redis/fixed-window-v1.lua"), List.class);
  }
}
