package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import lab.ratelimiter.gateway.application.RedisOutcome;
import lab.ratelimiter.gateway.application.TokenBucketStateResult;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
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
class RedisTokenBucketStateAdapterTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory connectionFactory;
  private static ReactiveStringRedisTemplate redis;
  private static RedisTokenBucketStateAdapter adapter;

  private final ClientIdentityExtractor identities = new ClientIdentityExtractor();

  @BeforeAll
  static void connect() {
    connectionFactory = newConnectionFactory();
    redis = new ReactiveStringRedisTemplate(connectionFactory, RedisSerializationContext.string());
    adapter = new RedisTokenBucketStateAdapter(redis, tokenBucketScript(), Duration.ofSeconds(2));
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
  void firstRequestUsesInitialBalanceAndBurstNeverExceedsIt() {
    TokenBucketPolicy policy = policy("initial", 1, 5, 3, 1, Duration.ofHours(1));
    LimiterIdentity identity = identity("initial-client");
    Instant activation = redisInstant();

    for (int request = 0; request < 3; request++) {
      assertThat(decide(policy, 1, activation, identity).decision().allowed()).isTrue();
    }
    TokenBucketStateResult rejected = decide(policy, 1, activation, identity);

    assertThat(rejected.decision().allowed()).isFalse();
    assertThat(rejected.remainingScaledTokens()).isZero();
    assertThat(rejected.redisOutcome()).isEqualTo(RedisOutcome.REJECTED);
    assertThat(hash(onlyKey()).get("tokens")).isEqualTo("0");
  }

  @Test
  void costGreaterThanOneDeductsExactlyAndRejectedRequestPreservesOneToken() {
    TokenBucketPolicy policy = policy("cost", 1, 10, 10, 1, Duration.ofHours(1));
    LimiterIdentity identity = identity("cost-client");
    Instant activation = redisInstant();

    for (int request = 0; request < 3; request++) {
      assertThat(decide(policy, 3, activation, identity).decision().allowed()).isTrue();
    }
    TokenBucketStateResult rejected = decide(policy, 3, activation, identity);

    assertThat(rejected.decision().allowed()).isFalse();
    assertThat(rejected.remainingScaledTokens()).isEqualTo(1_000);
    assertThat(hash(onlyKey()).get("tokens")).isEqualTo("1000");
  }

  @Test
  void continuousPartialRefillUsesRedisServerTime() {
    TokenBucketPolicy policy = policy("partial", 1, 10, 0, 1, Duration.ofSeconds(1));
    LimiterIdentity identity = identity("partial-client");
    Instant activation = redisInstant();
    assertThat(decide(policy, 1, activation, identity).decision().allowed()).isFalse();
    String key = onlyKey();

    await()
        .atMost(Duration.ofSeconds(2))
        .untilAsserted(
            () -> {
              TokenBucketStateResult partial = decide(policy, 1, activation, identity);
              assertThat(partial.decision().allowed()).isFalse();
              assertThat(partial.remainingScaledTokens()).isBetween(1L, 999L);
            });
    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> decide(policy, 1, activation, identity).decision().allowed());
    assertThat(redis.hasKey(key).block()).isTrue();
  }

  @Test
  void identitiesVersionsAndAlgorithmsHaveIndependentKeys() {
    TokenBucketPolicy first = policy("isolation", 1, 1, 1, 1, Duration.ofHours(1));
    TokenBucketPolicy secondVersion = policy("isolation", 2, 1, 1, 1, Duration.ofHours(1));
    Instant activation = redisInstant();

    assertThat(decide(first, 1, activation, identity("a")).decision().allowed()).isTrue();
    assertThat(decide(first, 1, activation, identity("a")).decision().allowed()).isFalse();
    assertThat(decide(first, 1, activation, identity("b")).decision().allowed()).isTrue();
    assertThat(decide(secondVersion, 1, activation, identity("a")).decision().allowed()).isTrue();

    assertThat(redis.keys("ratelimit:*").collectList().block()).hasSize(3);
    assertThat(redis.keys("*fixed-window*").collectList().block()).isEmpty();
  }

  @Test
  void ttlExistsAndExpiredStateReconstructsFullFromActivationAnchor() {
    TokenBucketPolicy policy = policy("expiry", 1, 1, 0, 1, Duration.ofMillis(100));
    LimiterIdentity identity = identity("expiry-client");
    Instant activation = redisInstant();
    TokenBucketStateResult initial = decide(policy, 1, activation, identity);
    assertThat(initial.decision().allowed()).isFalse();
    String key = onlyKey();
    assertThat(redis.getExpire(key).block()).isPositive();

    await()
        .atMost(Duration.ofSeconds(2))
        .until(() -> Boolean.FALSE.equals(redis.hasKey(key).block()));

    TokenBucketStateResult reconstructed = decide(policy, 1, activation, identity);
    assertThat(reconstructed.decision().allowed()).isTrue();
    assertThat(reconstructed.stateReconstructed()).isTrue();
  }

  @Test
  void malformedStoredFieldsFailSafelyWithoutMutation() {
    TokenBucketPolicy policy = policy("malformed", 1, 5, 5, 1, Duration.ofSeconds(1));
    LimiterIdentity identity = identity("malformed-client");
    Instant activation = redisInstant();
    String key = RedisTokenBucketKey.create(policy, identity).value();
    redis
        .opsForHash()
        .putAll(key, Map.of("tokens", "bad", "last_ms", "0", "refill_remainder", "0"))
        .block();

    assertThatThrownBy(() -> decide(policy, 1, activation, identity))
        .isInstanceOf(RedisStateException.class)
        .satisfies(
            error ->
                assertThat(((RedisStateException) error).outcome())
                    .isEqualTo(RedisOutcome.MALFORMED_STATE));
    assertThat(hash(key).get("tokens")).isEqualTo("bad");
  }

  @Test
  void malformedTimestampRemainderAndUnexpectedFieldsFailSafely() {
    TokenBucketPolicy policy = policy("malformed-shape", 1, 5, 5, 1, Duration.ofSeconds(1));
    LimiterIdentity identity = identity("malformed-shape-client");
    Instant activation = redisInstant();
    String key = RedisTokenBucketKey.create(policy, identity).value();
    for (Map<Object, Object> malformed :
        List.<Map<Object, Object>>of(
            Map.of("tokens", "5000", "last_ms", "1.0", "refill_remainder", "0"),
            Map.of("tokens", "5000", "last_ms", "9007199254740992", "refill_remainder", "0"),
            Map.of("tokens", "4000", "last_ms", "0", "refill_remainder", "1000"),
            Map.of("tokens", "4000", "last_ms", "0", "refill_remainder", "0", "unexpected", "1"))) {
      redis.delete(key).block();
      redis.opsForHash().putAll(key, malformed).block();
      assertThatThrownBy(() -> decide(policy, 1, activation, identity))
          .isInstanceOf(RedisStateException.class)
          .satisfies(
              error ->
                  assertThat(((RedisStateException) error).outcome())
                      .isEqualTo(RedisOutcome.MALFORMED_STATE));
      assertThat(hash(key)).containsAllEntriesOf(malformed);
    }
  }

  @Test
  void scriptFlushRecoveryDoesNotDoubleDeduct() throws Exception {
    TokenBucketPolicy policy = policy("script-cache", 1, 5, 5, 1, Duration.ofHours(1));
    LimiterIdentity identity = identity("cache-client");
    Instant activation = redisInstant();
    assertThat(decide(policy, 1, activation, identity).remainingScaledTokens()).isEqualTo(4_000);

    REDIS.execInContainer("redis-cli", "SCRIPT", "FLUSH");

    assertThat(decide(policy, 1, activation, identity).remainingScaledTokens()).isEqualTo(3_000);
    assertThat(hash(onlyKey()).get("tokens")).isEqualTo("3000");
  }

  @Test
  void repeatedIndependentClientConcurrencyNeverMultipliesCapacity() {
    List<LettuceConnectionFactory> factories =
        java.util.stream.IntStream.range(0, 3).mapToObj(ignored -> newConnectionFactory()).toList();
    List<RedisTokenBucketStateAdapter> adapters =
        factories.stream()
            .map(
                factory ->
                    new RedisTokenBucketStateAdapter(
                        new ReactiveStringRedisTemplate(
                            factory, RedisSerializationContext.string()),
                        tokenBucketScript(),
                        Duration.ofSeconds(2)))
            .toList();
    try {
      for (int repetition = 0; repetition < 10; repetition++) {
        clearRedis();
        TokenBucketPolicy policy =
            policy("concurrency-" + repetition, 1, 20, 20, 1, Duration.ofHours(1));
        LimiterIdentity identity = identity("shared-client");
        Instant activation = redisInstant();
        CountDownLatch start = new CountDownLatch(1);
        try (var callers = Executors.newFixedThreadPool(60)) {
          List<Future<TokenBucketStateResult>> futures =
              java.util.stream.IntStream.range(0, 60)
                  .mapToObj(
                      request ->
                          callers.submit(
                              () -> {
                                start.await();
                                return adapters
                                    .get(request % adapters.size())
                                    .decide(policy, 1, activation, identity)
                                    .block();
                              }))
                  .toList();
          start.countDown();
          assertThat(
                  futures.stream()
                      .map(RedisTokenBucketStateAdapterTest::get)
                      .filter(result -> result.decision().allowed())
                      .count())
              .isEqualTo(20);
          assertThat(hash(onlyKey()).get("tokens")).isEqualTo("0");
          assertThat(redis.getExpire(onlyKey()).block()).isPositive();
        }
      }
    } finally {
      factories.forEach(LettuceConnectionFactory::destroy);
    }
  }

  private static LettuceConnectionFactory newConnectionFactory() {
    LettuceConnectionFactory factory =
        new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    return factory;
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RedisScript<List<?>> tokenBucketScript() {
    return (RedisScript)
        RedisScript.of(
            new org.springframework.core.io.ClassPathResource("redis/token-bucket-v1.lua"),
            List.class);
  }

  private TokenBucketStateResult decide(
      TokenBucketPolicy policy, long cost, Instant activation, LimiterIdentity identity) {
    return adapter.decide(policy, cost, activation, identity).block();
  }

  private LimiterIdentity identity(String client) {
    return identities.extract(client, "catalog.items").orElseThrow();
  }

  private static TokenBucketPolicy policy(
      String id, long version, long capacity, long initial, long refill, Duration period) {
    return new TokenBucketPolicy(
        new PolicyId(id), new PolicyVersion(version), capacity, initial, refill, period);
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

  private static TokenBucketStateResult get(Future<TokenBucketStateResult> future) {
    try {
      return future.get();
    } catch (Exception exception) {
      throw new AssertionError("concurrent Token Bucket call failed", exception);
    }
  }
}
