package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.SlidingWindowCounterStateResult;
import lab.ratelimiter.gateway.domain.limiter.InMemorySlidingWindowCounterRateLimiter;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.domain.limiter.SlidingWindowCounterPolicy;
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
class RedisSlidingWindowCounterStateAdapterTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static ReactiveStringRedisTemplate redis;
  private static RedisSlidingWindowCounterStateAdapter adapter;

  private final ClientIdentityExtractor identities = new ClientIdentityExtractor();

  @BeforeAll
  static void connect() {
    connectionFactory = newConnectionFactory();
    redis = new ReactiveStringRedisTemplate(connectionFactory, RedisSerializationContext.string());
    adapter =
        new RedisSlidingWindowCounterStateAdapter(
            redis, slidingCounterScript(), Duration.ofSeconds(2));
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
  void firstAndSameWindowRequestsIncrementExactlyAndRejectionDoesNotIncrement() {
    SlidingWindowCounterPolicy policy = policy("same", 1, 5, 10_000);
    LimiterIdentity identity = identity("same-client");

    for (int request = 0; request < 5; request++) {
      assertThat(decide(policy, 1, identity).decision().allowed()).isTrue();
    }
    SlidingWindowCounterStateResult rejected = decide(policy, 1, identity);

    assertThat(rejected.decision().allowed()).isFalse();
    assertThat(rejected.currentWindowCount()).isEqualTo(5);
    assertThat(hash(onlyKey()).get("current_count")).isEqualTo("5");
    assertThat(redis.getExpire(onlyKey()).block()).isPositive();
  }

  @Test
  void exactObservedWindowTransitionWeightsPreviousTrafficAndRotatesOnce() {
    SlidingWindowCounterPolicy policy = policy("rotation", 1, 5, 300);
    LimiterIdentity identity = identity("rotation-client");
    SlidingWindowCounterStateResult prepared = decide(policy, 1, identity);
    decide(policy, 1, identity);
    decide(policy, 1, identity);
    long preparedWindow = prepared.currentWindowId();

    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> redisInstant().toEpochMilli() / 300 > preparedWindow);
    SlidingWindowCounterStateResult transitioned = decide(policy, 3, identity);

    assertThat(transitioned.currentWindowId()).isEqualTo(preparedWindow + 1);
    assertThat(transitioned.previousWindowCount()).isEqualTo(3);
    assertThat(transitioned.rotation()).isEqualTo(SlidingCounterRotation.ADVANCE_ONE);
    assertThat(transitioned.decision().allowed()).isFalse();
    assertThat(transitioned.currentWindowCount()).isZero();
  }

  @Test
  void identitiesVersionsAndAlgorithmNamespacesAreIndependent() {
    SlidingWindowCounterPolicy first = policy("isolation", 1, 1, 10_000);
    SlidingWindowCounterPolicy second = policy("isolation", 2, 1, 10_000);

    assertThat(decide(first, 1, identity("a")).decision().allowed()).isTrue();
    assertThat(decide(first, 1, identity("a")).decision().allowed()).isFalse();
    assertThat(decide(first, 1, identity("b")).decision().allowed()).isTrue();
    assertThat(decide(second, 1, identity("a")).decision().allowed()).isTrue();

    assertThat(redis.keys("ratelimit:*").collectList().block()).hasSize(3);
    assertThat(redis.keys("*fixed-window*").collectList().block()).isEmpty();
    assertThat(redis.keys("*token-bucket*").collectList().block()).isEmpty();
  }

  @Test
  void expiredStateReconstructsCleanlyAndScriptFlushRecoversOnce() throws Exception {
    SlidingWindowCounterPolicy policy = policy("expiry", 1, 1, 100);
    LimiterIdentity identity = identity("expiry-client");
    assertThat(decide(policy, 1, identity).decision().allowed()).isTrue();
    String key = onlyKey();
    assertThat(redis.getExpire(key).block()).isPositive();
    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> Boolean.FALSE.equals(redis.hasKey(key).block()));

    assertThat(decide(policy, 1, identity).decision().allowed()).isTrue();
    REDIS.execInContainer("redis-cli", "SCRIPT", "FLUSH");
    SlidingWindowCounterStateResult afterFlush = decide(policy, 1, identity);
    assertThat(afterFlush.decision().allowed()).isFalse();
    assertThat(hash(onlyKey()).get("current_count"))
        .isEqualTo(Long.toString(afterFlush.currentWindowCount()));
  }

  @Test
  void everyMalformedStoredFieldFailsWithoutMutation() {
    SlidingWindowCounterPolicy policy = policy("malformed", 1, 5, 1_000);
    LimiterIdentity identity = identity("malformed-client");
    String key = RedisSlidingWindowCounterKey.create(policy, identity).value();
    for (Map<Object, Object> malformed :
        List.<Map<Object, Object>>of(
            Map.of("window_id", "bad", "current_count", "0", "previous_count", "0"),
            Map.of("window_id", "0", "current_count", "bad", "previous_count", "0"),
            Map.of("window_id", "0", "current_count", "0", "previous_count", "bad"),
            Map.of("window_id", "0", "current_count", "0", "previous_count", "0", "extra", "1"))) {
      redis.delete(key).block();
      redis.opsForHash().putAll(key, malformed).block();
      assertThatThrownBy(() -> decide(policy, 1, identity))
          .isInstanceOf(RedisStateException.class)
          .satisfies(
              error ->
                  assertThat(((RedisStateException) error).outcome())
                      .isEqualTo(RedisOutcome.MALFORMED_STATE));
      assertThat(hash(key)).containsAllEntriesOf(malformed);
    }
  }

  @Test
  void repeatedIndependentClientConcurrencySharesOneCapacityAndKeepsTtl() {
    List<LettuceConnectionFactory> factories =
        java.util.stream.IntStream.range(0, 3).mapToObj(ignored -> newConnectionFactory()).toList();
    List<RedisSlidingWindowCounterStateAdapter> adapters =
        factories.stream()
            .map(
                factory ->
                    new RedisSlidingWindowCounterStateAdapter(
                        new ReactiveStringRedisTemplate(
                            factory, RedisSerializationContext.string()),
                        slidingCounterScript(),
                        Duration.ofSeconds(2)))
            .toList();
    try {
      for (int repetition = 0; repetition < 10; repetition++) {
        clearRedis();
        SlidingWindowCounterPolicy policy = policy("concurrency-" + repetition, 1, 20, 10_000);
        LimiterIdentity identity = identity("shared-client");
        CountDownLatch start = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(60)) {
          List<Future<SlidingWindowCounterStateResult>> futures =
              java.util.stream.IntStream.range(0, 60)
                  .mapToObj(
                      request ->
                          callers.submit(
                              () -> {
                                start.await();
                                return adapters
                                    .get(request % adapters.size())
                                    .decide(policy, 1, identity)
                                    .block();
                              }))
                  .toList();
          start.countDown();
          assertThat(
                  futures.stream()
                      .map(RedisSlidingWindowCounterStateAdapterTest::get)
                      .filter(result -> result.decision().allowed())
                      .count())
              .isEqualTo(20);
          assertThat(hash(onlyKey()).get("current_count")).isEqualTo("20");
          assertThat(redis.getExpire(onlyKey()).block()).isPositive();
        }
      }
    } finally {
      factories.forEach(LettuceConnectionFactory::destroy);
    }
  }

  @Test
  void realRedisTraceMatchesPureAndPhaseOneModelsAndRecordsExactLogDifference() {
    SlidingWindowCounterPolicy policy = policy("compatibility", 1, 5, 3_600_000);
    LimiterIdentity identity = identity("compatibility-client");
    SlidingCounterParameters parameters = new SlidingCounterParameters(5, 3_600_000, 1);
    SlidingCounterState pureState = null;
    AdjustableClock referenceClock = null;
    InMemorySlidingWindowCounterRateLimiter phaseOne = null;
    List<Long> exactLog = new ArrayList<>();

    for (int request = 0; request < 7; request++) {
      SlidingWindowCounterStateResult actual = decide(policy, 1, identity);
      long now = actual.redisNow().toEpochMilli();
      if (referenceClock == null) {
        referenceClock = new AdjustableClock(actual.redisNow());
        phaseOne = new InMemorySlidingWindowCounterRateLimiter(policy, referenceClock);
        pureState = new SlidingCounterState(actual.currentWindowId(), 0, 0);
      } else {
        referenceClock.set(actual.redisNow());
      }

      SlidingCounterTransition pure =
          RedisSlidingWindowCounterArithmetic.decide(parameters, pureState, now);
      pureState = pure.state();
      exactLog.removeIf(timestamp -> timestamp <= now - parameters.windowMilliseconds());
      boolean exactAllowed = exactLog.size() + 1 <= policy.limit();
      if (exactAllowed) {
        exactLog.add(now);
      }
      var phaseOneDecision = phaseOne.decide(new RateLimitRequest(1));

      assertThat(actual.decision().allowed()).isEqualTo(pure.allowed());
      assertThat(actual.decision().allowed()).isEqualTo(phaseOneDecision.allowed());
      assertThat(actual.currentWindowCount()).isEqualTo(pure.state().currentCount());
      assertThat(actual.previousWindowCount()).isEqualTo(pure.state().previousCount());
      assertThat(actual.weightedNumerator()).isEqualTo(pure.weightedNumerator());
      assertThat(actual.weightedNumerator() - exactLog.size() * parameters.windowMilliseconds())
          .isZero();
      assertThat(actual.decision().allowed()).isEqualTo(exactAllowed);
    }
  }

  private static LettuceConnectionFactory newConnectionFactory() {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    return factory;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RedisScript<List<?>> slidingCounterScript() {
    return (RedisScript)
        RedisScript.of(
            new org.springframework.core.io.ClassPathResource(
                "redis/sliding-window-counter-v1.lua"),
            List.class);
  }

  private SlidingWindowCounterStateResult decide(
      SlidingWindowCounterPolicy policy, long cost, LimiterIdentity identity) {
    return adapter.decide(policy, cost, identity).block();
  }

  private LimiterIdentity identity(String client) {
    return identities.extract(client, "catalog.items").orElseThrow();
  }

  private static SlidingWindowCounterPolicy policy(
      String id, long version, long limit, long windowMilliseconds) {
    return new SlidingWindowCounterPolicy(
        new PolicyId(id), new PolicyVersion(version), limit, Duration.ofMillis(windowMilliseconds));
  }

  private static Instant redisInstant() {
    return Instant.ofEpochMilli(
        redis.execute(connection -> connection.serverCommands().time()).single().block());
  }

  private static Map<Object, Object> hash(String key) {
    return redis
        .opsForHash()
        .entries(key)
        .collectMap(Map.Entry::getKey, Map.Entry::getValue)
        .block();
  }

  private static String onlyKey() {
    return redis.keys("ratelimit:*").collectList().block().getFirst();
  }

  private static SlidingWindowCounterStateResult get(
      Future<SlidingWindowCounterStateResult> future) {
    try {
      return future.get();
    } catch (Exception exception) {
      throw new AssertionError("concurrent Sliding Counter call failed", exception);
    }
  }

  private static final class AdjustableClock extends Clock {
    private Instant instant;

    private AdjustableClock(Instant instant) {
      this.instant = instant;
    }

    private void set(Instant instant) {
      this.instant = instant;
    }

    @Override
    public ZoneId getZone() {
      return ZoneId.of("UTC");
    }

    @Override
    public Clock withZone(ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return instant;
    }
  }
}
