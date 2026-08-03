package lab.ratelimiter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;
import lab.ratelimiter.gateway.application.FailureMode;
import lab.ratelimiter.gateway.application.StateBackend;
import lab.ratelimiter.gateway.config.GatewayProperties.PolicyProperties;
import lab.ratelimiter.gateway.domain.limiter.AlgorithmType;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.StaticPolicySnapshot;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class StaticPolicyConfigurationTest {

  private static final String[] VALID_PROPERTIES = {
    "rate-limiter.gateway.catalog-base-url=http://catalog:8000",
    "rate-limiter.gateway.backend-timeout=2s",
    "rate-limiter.gateway.state-backend=REDIS",
    "rate-limiter.gateway.failure-mode=FAIL_CLOSED",
    "rate-limiter.gateway.instance-id=gateway-1",
    "rate-limiter.gateway.expose-instance-header=true",
    "rate-limiter.gateway.redis-command-timeout=750ms",
    "rate-limiter.gateway.policies[0].id=catalog-client-fixed-window",
    "rate-limiter.gateway.policies[0].version=1",
    "rate-limiter.gateway.policies[0].route-id=catalog.items",
    "rate-limiter.gateway.policies[0].path=/proxy/catalog/items",
    "rate-limiter.gateway.policies[0].method=GET",
    "rate-limiter.gateway.policies[0].algorithm=FIXED_WINDOW",
    "rate-limiter.gateway.policies[0].limit=5",
    "rate-limiter.gateway.policies[0].window=10s"
  };

  private final ApplicationContextRunner contextRunner =
      new ApplicationContextRunner().withUserConfiguration(StaticPolicyConfiguration.class);

  @Test
  void validStaticPolicyConfigurationLoadsAndMatchesCatalogRoute() {
    contextRunner
        .withPropertyValues(VALID_PROPERTIES)
        .run(
            context -> {
              assertThat(context).hasNotFailed();
              StaticPolicySnapshot snapshot = context.getBean(StaticPolicySnapshot.class);
              GatewayProperties properties = context.getBean(GatewayProperties.class);

              assertThat(properties.stateBackend()).isEqualTo(StateBackend.REDIS);
              assertThat(properties.failureMode()).isEqualTo(FailureMode.FAIL_CLOSED);
              assertThat(properties.instanceId()).isEqualTo("gateway-1");
              assertThat(properties.exposeInstanceHeader()).isTrue();
              assertThat(properties.redisCommandTimeout()).isEqualTo(Duration.ofMillis(750));

              assertThat(snapshot.match("GET", "/proxy/catalog/items"))
                  .get()
                  .satisfies(StaticPolicyConfigurationTest::assertCatalogPolicy);
              assertThat(snapshot.match("POST", "/proxy/catalog/items")).isEmpty();
              assertThat(snapshot.match("GET", "/proxy/catalog/missing")).isEmpty();
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidOverrides")
  void invalidStaticPolicyConfigurationFailsStartup(String description, String override) {
    contextRunner
        .withPropertyValues(VALID_PROPERTIES)
        .withPropertyValues(override)
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure()).hasMessageContaining("static policy");
            });
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("invalidRuntimeOverrides")
  void invalidRuntimeConfigurationFailsStartup(String description, String override) {
    contextRunner
        .withPropertyValues(VALID_PROPERTIES)
        .withPropertyValues(override)
        .run(context -> assertThat(context).hasFailed());
  }

  @Test
  void duplicateMethodAndPathFailsStartup() {
    contextRunner
        .withPropertyValues(VALID_PROPERTIES)
        .withPropertyValues(
            "rate-limiter.gateway.policies[1].id=duplicate",
            "rate-limiter.gateway.policies[1].version=1",
            "rate-limiter.gateway.policies[1].route-id=catalog.duplicate",
            "rate-limiter.gateway.policies[1].path=/proxy/catalog/items",
            "rate-limiter.gateway.policies[1].method=GET",
            "rate-limiter.gateway.policies[1].algorithm=FIXED_WINDOW",
            "rate-limiter.gateway.policies[1].limit=2",
            "rate-limiter.gateway.policies[1].window=1s")
        .run(
            context -> {
              assertThat(context).hasFailed();
              assertThat(context.getStartupFailure())
                  .hasMessageContaining("duplicate static route");
            });
  }

  @Test
  void compilerRejectsDistinctInvalidUriAndMissingValueForms() {
    PolicyProperties valid = validPolicy();
    assertThatCode(
            () ->
                StaticPolicyCompiler.compile(
                    properties(URI.create("https://catalog:8443"), Duration.ofSeconds(1), valid)))
        .doesNotThrowAnyException();

    List<GatewayProperties> invalid =
        List.of(
            properties(null, Duration.ofSeconds(1), valid),
            properties(URI.create("/catalog"), Duration.ofSeconds(1), valid),
            properties(URI.create("http:/catalog"), Duration.ofSeconds(1), valid),
            properties(URI.create("http://user@catalog"), Duration.ofSeconds(1), valid),
            properties(URI.create("http://catalog?debug=true"), Duration.ofSeconds(1), valid),
            properties(URI.create("http://catalog#fragment"), Duration.ofSeconds(1), valid),
            properties(URI.create("http://catalog"), null, valid),
            properties(URI.create("http://catalog"), Duration.ofSeconds(-1), valid),
            properties(URI.create("http://catalog"), Duration.ofNanos(1), valid),
            new GatewayProperties(
                URI.create("http://catalog"),
                Duration.ofSeconds(1),
                StateBackend.REDIS,
                FailureMode.FAIL_CLOSED,
                "gateway-1",
                true,
                Duration.ofMillis(750),
                List.of()));

    for (GatewayProperties properties : invalid) {
      assertThatThrownBy(() -> StaticPolicyCompiler.compile(properties))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("static policy");
    }
  }

  @Test
  void compilerRejectsDistinctInvalidPolicyValueAndPathForms() {
    List<PolicyProperties> invalid =
        List.of(
            policy(
                null,
                1L,
                "catalog.items",
                "/proxy/catalog/items",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                null,
                "catalog.items",
                "/proxy/catalog/items",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                null,
                "/proxy/catalog/items",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/[",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/items?debug=true",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/items#fragment",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/../items",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/items",
                null,
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/items",
                "GET",
                null,
                5L,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/items",
                "GET",
                "FIXED_WINDOW",
                null,
                Duration.ofSeconds(10)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/items",
                "GET",
                "FIXED_WINDOW",
                5L,
                null),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/items",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofSeconds(-1)),
            policy(
                "policy",
                1L,
                "catalog.items",
                "/proxy/catalog/items",
                "GET",
                "FIXED_WINDOW",
                5L,
                Duration.ofNanos(1)));

    for (PolicyProperties policy : invalid) {
      assertThatThrownBy(
              () ->
                  StaticPolicyCompiler.compile(
                      properties(URI.create("http://catalog"), Duration.ofSeconds(1), policy)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("static policy");
    }
  }

  private static Stream<Arguments> invalidOverrides() {
    return Stream.of(
        Arguments.of("missing policy ID", "rate-limiter.gateway.policies[0].id="),
        Arguments.of("invalid route ID", "rate-limiter.gateway.policies[0].route-id=Catalog Items"),
        Arguments.of("invalid route path", "rate-limiter.gateway.policies[0].path=catalog/items"),
        Arguments.of("invalid method", "rate-limiter.gateway.policies[0].method=POST"),
        Arguments.of(
            "unsupported algorithm", "rate-limiter.gateway.policies[0].algorithm=TOKEN_BUCKET"),
        Arguments.of("invalid limit", "rate-limiter.gateway.policies[0].limit=0"),
        Arguments.of("excessive limit", "rate-limiter.gateway.policies[0].limit=1000001"),
        Arguments.of("invalid window", "rate-limiter.gateway.policies[0].window=0s"),
        Arguments.of("excessive window", "rate-limiter.gateway.policies[0].window=86400001ms"),
        Arguments.of("invalid backend URI", "rate-limiter.gateway.catalog-base-url=ftp://catalog"),
        Arguments.of("invalid timeout", "rate-limiter.gateway.backend-timeout=0s"));
  }

  private static Stream<Arguments> invalidRuntimeOverrides() {
    return Stream.of(
        Arguments.of("unknown backend", "rate-limiter.gateway.state-backend=UNKNOWN"),
        Arguments.of("unknown failure mode", "rate-limiter.gateway.failure-mode=UNKNOWN"),
        Arguments.of("blank instance", "rate-limiter.gateway.instance-id="),
        Arguments.of("overlong instance", "rate-limiter.gateway.instance-id=" + "x".repeat(65)),
        Arguments.of("zero command timeout", "rate-limiter.gateway.redis-command-timeout=0ms"),
        Arguments.of(
            "excessive command timeout", "rate-limiter.gateway.redis-command-timeout=11s"));
  }

  private static void assertCatalogPolicy(CompiledPolicy compiled) {
    assertThat(compiled.routeId()).isEqualTo("catalog.items");
    assertThat(compiled.path()).isEqualTo("/proxy/catalog/items");
    assertThat(compiled.method()).isEqualTo("GET");
    assertThat(compiled.policy().policyId().value()).isEqualTo("catalog-client-fixed-window");
    assertThat(compiled.policy().policyVersion().value()).isEqualTo(1);
    assertThat(compiled.policy().algorithm()).isEqualTo(AlgorithmType.FIXED_WINDOW);
    assertThat(compiled.policy().limit()).isEqualTo(5);
    assertThat(compiled.policy().window()).hasSeconds(10);
  }

  private static GatewayProperties properties(URI uri, Duration timeout, PolicyProperties policy) {
    return new GatewayProperties(
        uri,
        timeout,
        StateBackend.REDIS,
        FailureMode.FAIL_CLOSED,
        "gateway-1",
        true,
        Duration.ofMillis(750),
        List.of(policy));
  }

  private static PolicyProperties validPolicy() {
    return policy(
        "catalog-client-fixed-window",
        1L,
        "catalog.items",
        "/proxy/catalog/items",
        "GET",
        "FIXED_WINDOW",
        5L,
        Duration.ofSeconds(10));
  }

  private static PolicyProperties policy(
      String id,
      Long version,
      String routeId,
      String path,
      String method,
      String algorithm,
      Long limit,
      Duration window) {
    return new PolicyProperties(id, version, routeId, path, method, algorithm, limit, window);
  }
}
