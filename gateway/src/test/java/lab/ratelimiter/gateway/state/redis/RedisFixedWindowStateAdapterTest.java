package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lab.ratelimiter.gateway.application.FixedWindowStateResult;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.identity.LimiterIdentity;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class RedisFixedWindowStateAdapterTest {

  private static final DockerImageName REDIS_IMAGE = DockerImageName.parse("redis:7.4.2-alpine");

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(REDIS_IMAGE).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static ReactiveStringRedisTemplate redis;
  private static RedisFixedWindowStateAdapter adapter;

  private final ClientIdentityExtractor identities = new ClientIdentityExtractor();

  @BeforeAll
  static void connect() {
    connectionFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    connectionFactory.afterPropertiesSet();
    redis = new ReactiveStringRedisTemplate(connectionFactory, RedisSerializationContext.string());
    adapter = new RedisFixedWindowStateAdapter(redis, fixedWindowScript(), Duration.ofSeconds(2));
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
  void firstAndExactLimitAreAllowedAndRequestAboveLimitIsRejected() {
    FixedWindowPolicy policy = policy("catalog", 1, 5, Duration.ofSeconds(10));
    LimiterIdentity identity = identities.extract("client-a", "catalog.items").orElseThrow();

    for (int request = 1; request <= 5; request++) {
      FixedWindowStateResult result = decide(policy, identity);
      assertThat(result.decision().allowed()).isTrue();
      assertThat(result.currentCount()).isEqualTo(request);
      assertThat(result.decision().remaining()).isEqualTo(5 - request);
      assertThat(result.redisOutcome()).isEqualTo(RedisOutcome.ALLOWED);
    }

    FixedWindowStateResult rejected = decide(policy, identity);
    assertThat(rejected.decision().allowed()).isFalse();
    assertThat(rejected.currentCount()).isEqualTo(5);
    assertThat(rejected.decision().remaining()).isZero();
    assertThat(rejected.decision().retryAfter()).isPresent();
    assertThat(rejected.resetAfter()).isPositive().isLessThanOrEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void identitiesRoutesAndPolicyVersionsHaveIndependentState() {
    FixedWindowPolicy versionOne = policy("catalog", 1, 1, Duration.ofSeconds(10));
    FixedWindowPolicy versionTwo = policy("catalog", 2, 1, Duration.ofSeconds(10));
    LimiterIdentity first = identities.extract("client-a", "catalog.items").orElseThrow();
    LimiterIdentity second = identities.extract("client-b", "catalog.items").orElseThrow();
    LimiterIdentity otherRoute = identities.extract("client-a", "catalog.details").orElseThrow();

    assertThat(decide(versionOne, first).decision().allowed()).isTrue();
    assertThat(decide(versionOne, first).decision().allowed()).isFalse();
    assertThat(decide(versionOne, second).decision().allowed()).isTrue();
    assertThat(decide(versionOne, otherRoute).decision().allowed()).isTrue();
    assertThat(decide(versionTwo, first).decision().allowed()).isTrue();
  }

  @Test
  void assignsAndRepairsDeterministicTtlWithoutExtendingTheWindow() {
    FixedWindowPolicy policy = policy("ttl", 1, 5, Duration.ofSeconds(10));
    LimiterIdentity identity = identities.extract("ttl-client", "catalog.items").orElseThrow();
    FixedWindowStateResult first = decide(policy, identity);
    String key = onlyKey();
    Duration initialTtl = redis.getExpire(key).block();

    assertThat(initialTtl).isPositive().isLessThanOrEqualTo(first.resetAfter());
    assertThat(redis.persist(key).block()).isTrue();
    assertThat(redis.getExpire(key).block()).isZero();

    FixedWindowStateResult second = decide(policy, identity);
    Duration repairedTtl = redis.getExpire(key).block();

    assertThat(second.currentCount()).isEqualTo(2);
    assertThat(repairedTtl).isPositive().isLessThanOrEqualTo(second.resetAfter());
  }

  @Test
  void expiredWindowCreatesFreshStateWithoutSleepingTheTestThread() {
    FixedWindowPolicy policy = policy("expiry", 1, 1, Duration.ofMillis(150));
    LimiterIdentity identity = identities.extract("expiry-client", "catalog.items").orElseThrow();
    assertThat(decide(policy, identity).decision().allowed()).isTrue();
    String oldKey = onlyKey();
    assertThat(decide(policy, identity).decision().allowed()).isFalse();

    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> Boolean.FALSE.equals(redis.hasKey(oldKey).block()));

    FixedWindowStateResult nextWindow = decide(policy, identity);
    assertThat(nextWindow.decision().allowed()).isTrue();
    assertThat(nextWindow.currentCount()).isEqualTo(1);
    assertThat(onlyKey()).isNotEqualTo(oldKey);
  }

  @Test
  void scriptCacheMissFallsBackSafelyWithoutDoubleIncrementing() throws Exception {
    FixedWindowPolicy policy = policy("script-cache", 1, 5, Duration.ofSeconds(10));
    LimiterIdentity identity = identities.extract("cache-client", "catalog.items").orElseThrow();
    assertThat(decide(policy, identity).currentCount()).isEqualTo(1);

    REDIS.execInContainer("redis-cli", "SCRIPT", "FLUSH");

    assertThat(decide(policy, identity).currentCount()).isEqualTo(2);
    assertThat(redis.opsForValue().get(onlyKey()).block()).isEqualTo("2");
  }

  @Test
  void malformedStoredCounterFailsSafely() {
    FixedWindowPolicy policy = policy("malformed", 1, 5, Duration.ofSeconds(10));
    LimiterIdentity identity = identities.extract("bad-client", "catalog.items").orElseThrow();
    long redisNow =
        redis.execute(connection -> connection.serverCommands().time()).single().block();
    long windowId = Math.floorDiv(redisNow, policy.window().toMillis());
    String key = RedisFixedWindowKey.create(policy, identity, windowId).value();
    redis.opsForValue().set(key, "not-an-integer", Duration.ofSeconds(10)).block();

    assertThatThrownBy(() -> decide(policy, identity))
        .isInstanceOf(RedisStateException.class)
        .satisfies(
            error ->
                assertThat(((RedisStateException) error).outcome())
                    .isEqualTo(RedisOutcome.MALFORMED_STATE));
  }

  @Test
  void concurrentRequestsThroughIndependentClientsNeverAdmitBeyondTheLimit() throws Exception {
    List<LettuceConnectionFactory> factories =
        java.util.stream.IntStream.range(0, 3).mapToObj(ignored -> newConnectionFactory()).toList();
    List<RedisFixedWindowStateAdapter> adapters =
        factories.stream()
            .map(
                factory ->
                    new RedisFixedWindowStateAdapter(
                        new ReactiveStringRedisTemplate(
                            factory, RedisSerializationContext.string()),
                        fixedWindowScript(),
                        Duration.ofSeconds(2)))
            .toList();

    try {
      for (int repetition = 0; repetition < 20; repetition++) {
        redis.execute(connection -> connection.serverCommands().flushDb()).then().block();
        FixedWindowPolicy policy = policy("concurrency-" + repetition, 1, 50, Duration.ofHours(1));
        LimiterIdentity identity =
            identities.extract("shared-client", "catalog.items").orElseThrow();
        CountDownLatch start = new CountDownLatch(1);

        try (var callers = Executors.newFixedThreadPool(100)) {
          List<Future<FixedWindowStateResult>> results =
              java.util.stream.IntStream.range(0, 100)
                  .mapToObj(
                      request ->
                          callers.submit(
                              () -> {
                                start.await();
                                return adapters
                                    .get(request % adapters.size())
                                    .decide(policy, identity, new RateLimitRequest(1))
                                    .block();
                              }))
                  .toList();

          start.countDown();
          long allowed =
              results.stream()
                  .map(RedisFixedWindowStateAdapterTest::get)
                  .filter(result -> result.decision().allowed())
                  .count();

          assertThat(allowed).isEqualTo(50);
          String key = onlyKey();
          assertThat(redis.opsForValue().get(key).block()).isEqualTo("50");
          assertThat(redis.getExpire(key).block()).isPositive();
        }
      }
    } finally {
      factories.forEach(LettuceConnectionFactory::destroy);
    }
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RedisScript<List<?>> fixedWindowScript() {
    return (RedisScript)
        RedisScript.of(
            new org.springframework.core.io.ClassPathResource("redis/fixed-window-v1.lua"),
            List.class);
  }

  private static LettuceConnectionFactory newConnectionFactory() {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    return factory;
  }

  private static FixedWindowStateResult get(Future<FixedWindowStateResult> future) {
    try {
      return future.get();
    } catch (Exception exception) {
      throw new AssertionError("concurrent rate-limit call failed", exception);
    }
  }

  private static FixedWindowPolicy policy(String id, long version, long limit, Duration window) {
    return new FixedWindowPolicy(new PolicyId(id), new PolicyVersion(version), limit, window);
  }

  private static FixedWindowStateResult decide(FixedWindowPolicy policy, LimiterIdentity identity) {
    return adapter.decide(policy, identity, new RateLimitRequest(1)).block();
  }

  private static String onlyKey() {
    return redis.keys("ratelimit:*").collectList().block().getFirst();
  }
}
