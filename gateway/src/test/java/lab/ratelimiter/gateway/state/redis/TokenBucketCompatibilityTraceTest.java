package lab.ratelimiter.gateway.state.redis;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import lab.ratelimiter.gateway.application.TokenBucketStateResult;
import lab.ratelimiter.gateway.domain.limiter.InMemoryTokenBucketRateLimiter;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.domain.limiter.RateLimitDecision;
import lab.ratelimiter.gateway.domain.limiter.RateLimitRequest;
import lab.ratelimiter.gateway.domain.limiter.TokenBucketPolicy;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
class TokenBucketCompatibilityTraceTest {

  @Container
  private static final GenericContainer<?> REDIS =
      new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine")).withExposedPorts(6379);

  private static LettuceConnectionFactory factory;
  private static ReactiveStringRedisTemplate redis;

  @BeforeAll
  static void connect() {
    factory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
    factory.afterPropertiesSet();
    redis = new ReactiveStringRedisTemplate(factory, RedisSerializationContext.string());
  }

  @AfterAll
  static void disconnect() {
    factory.destroy();
  }

  @Test
  void timestampedTraceMatchesPhaseOneModelRedisArithmeticAndDerivedProductionLua()
      throws Exception {
    long activationMilliseconds = 1_000_000;
    MutableClock clock = new MutableClock(Instant.ofEpochMilli(activationMilliseconds));
    TokenBucketPolicy policy =
        new TokenBucketPolicy(
            new PolicyId("compatibility-trace"),
            new PolicyVersion(1),
            5,
            2,
            1,
            Duration.ofSeconds(1));
    TokenBucketParameters parameters =
        TokenBucketParameters.ofTokens(5, 2, 1, 1_000, 1, activationMilliseconds);
    InMemoryTokenBucketRateLimiter phaseOne = new InMemoryTokenBucketRateLimiter(policy, clock);
    var identity =
        new ClientIdentityExtractor().extract("trace-client", "catalog.items").orElseThrow();
    String key = RedisTokenBucketKey.create(policy, identity).value();
    RedisScript<List<?>> timestampScript = timestampControlledProductionScript();
    List<Long> timestamps =
        List.of(
            activationMilliseconds,
            activationMilliseconds + 400,
            activationMilliseconds + 1_000,
            activationMilliseconds + 1_500,
            activationMilliseconds + 2_750,
            activationMilliseconds + 6_000);
    AtomicInteger invocation = new AtomicInteger();
    RedisTokenBucketStateAdapter adapter =
        new RedisTokenBucketStateAdapter(
            (scriptKey, arguments) -> {
              List<String> timestamped = new ArrayList<>(arguments);
              timestamped.add(Long.toString(timestamps.get(invocation.getAndIncrement())));
              return redis
                  .execute(timestampScript, List.of(scriptKey), timestamped.toArray())
                  .single();
            },
            Duration.ofSeconds(2));
    TokenBucketState modelState = null;

    for (long timestamp : timestamps) {
      clock.set(Instant.ofEpochMilli(timestamp));
      RateLimitDecision reference = phaseOne.decide(new RateLimitRequest(1));
      TokenBucketTransition model =
          RedisTokenBucketArithmetic.decide(parameters, modelState, timestamp);
      modelState = model.state();
      TokenBucketStateResult lua =
          adapter.decide(policy, 1, Instant.ofEpochMilli(activationMilliseconds), identity).block();

      assertThat(model.allowed()).isEqualTo(reference.allowed());
      assertThat(model.state().tokensScaled() / TokenBucketParameters.SCALE)
          .isEqualTo(reference.remaining());
      assertThat(lua.decision().allowed()).isEqualTo(reference.allowed());
      assertThat(lua.remainingScaledTokens()).isEqualTo(model.state().tokensScaled());
      assertThat(lua.refillRemainder()).isEqualTo(model.state().refillRemainder());
    }
    assertThat(invocation).hasValue(timestamps.size());
    assertThat(redis.hasKey(key).block()).isTrue();
  }

  @SuppressWarnings({"rawtypes", "unchecked"})
  private static RedisScript<List<?>> timestampControlledProductionScript() throws Exception {
    String production =
        new ClassPathResource("redis/token-bucket-v1.lua")
            .getContentAsString(StandardCharsets.UTF_8);
    String derived =
        production
            .replace("if #KEYS ~= 1 or #ARGV ~= 8", "if #KEYS ~= 1 or #ARGV ~= 9")
            .replace(
                "local redis_time = redis.call(\"TIME\")\n"
                    + "local now_ms = redis_time[1] * 1000 + math.floor(redis_time[2] / 1000)",
                "local now_ms = safe_integer(ARGV[9])\n"
                    + "if now_ms == nil then return redis.error_reply(\"RATE_LIMIT_SCRIPT_ARGUMENT\") end");
    assertThat(derived).isNotEqualTo(production).contains("safe_integer(ARGV[9])");
    return (RedisScript) RedisScript.of(derived, List.class);
  }

  private static final class MutableClock extends Clock {

    private Instant instant;

    private MutableClock(Instant instant) {
      this.instant = instant;
    }

    private void set(Instant value) {
      instant = value;
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
