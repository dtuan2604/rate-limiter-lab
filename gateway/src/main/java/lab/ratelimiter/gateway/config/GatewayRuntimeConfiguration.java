package lab.ratelimiter.gateway.config;

import java.time.Clock;
import lab.ratelimiter.gateway.application.FixedWindowStateAdapter;
import lab.ratelimiter.gateway.application.RateLimitService;
import lab.ratelimiter.gateway.application.SlidingWindowCounterStateAdapter;
import lab.ratelimiter.gateway.application.TokenBucketStateAdapter;
import lab.ratelimiter.gateway.http.GatewayHttpHandler;
import lab.ratelimiter.gateway.http.GatewayRoutes;
import lab.ratelimiter.gateway.identity.ClientIdentityExtractor;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.proxy.CatalogBackendClient;
import lab.ratelimiter.gateway.proxy.CatalogReadinessIndicator;
import lab.ratelimiter.gateway.proxy.WebClientCatalogBackendClient;
import lab.ratelimiter.gateway.state.redis.RateLimitStateReadinessIndicator;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration(proxyBeanMethods = false)
public class GatewayRuntimeConfiguration {

  private static final int MAXIMUM_BACKEND_RESPONSE_BYTES = 1024 * 1024;

  @Bean
  Clock gatewayClock() {
    return Clock.systemUTC();
  }

  @Bean
  ClientIdentityExtractor clientIdentityExtractor() {
    return new ClientIdentityExtractor();
  }

  @Bean
  RateLimitService rateLimitService(
      FixedWindowStateAdapter stateAdapter,
      TokenBucketStateAdapter tokenBucketStateAdapter,
      SlidingWindowCounterStateAdapter slidingWindowCounterStateAdapter) {
    return new RateLimitService(
        stateAdapter, tokenBucketStateAdapter, slidingWindowCounterStateAdapter);
  }

  @Bean
  CatalogBackendClient catalogBackendClient(
      WebClient.Builder webClientBuilder, GatewayProperties properties) {
    WebClient webClient =
        webClientBuilder
            .clone()
            .baseUrl(properties.catalogBaseUrl().toString())
            .codecs(
                codecs -> codecs.defaultCodecs().maxInMemorySize(MAXIMUM_BACKEND_RESPONSE_BYTES))
            .build();
    return new WebClientCatalogBackendClient(webClient, properties.backendTimeout());
  }

  @Bean(name = "catalogBackend")
  ReactiveHealthIndicator catalogBackendHealth(CatalogBackendClient backendClient) {
    return new CatalogReadinessIndicator(backendClient);
  }

  @Bean(name = "rateLimitState")
  ReactiveHealthIndicator rateLimitStateHealth(
      ReactiveStringRedisTemplate redis,
      GatewayProperties properties,
      PolicySnapshotStore policies) {
    return new RateLimitStateReadinessIndicator(
        redis,
        properties.stateBackend(),
        () ->
            policies.current().policies().stream()
                    .allMatch(
                        policy ->
                            policy.failureMode()
                                == lab.ratelimiter.gateway.application.FailureMode.FAIL_OPEN)
                ? lab.ratelimiter.gateway.application.FailureMode.FAIL_OPEN
                : lab.ratelimiter.gateway.application.FailureMode.FAIL_CLOSED,
        properties.redisCommandTimeout());
  }

  @Bean
  GatewayHttpHandler gatewayHttpHandler(
      PolicySnapshotStore policies,
      ClientIdentityExtractor identityExtractor,
      RateLimitService rateLimitService,
      CatalogBackendClient backendClient,
      GatewayProperties properties) {
    return new GatewayHttpHandler(
        policies,
        identityExtractor,
        rateLimitService,
        backendClient,
        properties.instanceId(),
        properties.exposeInstanceHeader());
  }

  @Bean
  RouterFunction<ServerResponse> gatewayRoutes(GatewayHttpHandler handler) {
    return GatewayRoutes.routes(handler);
  }
}
