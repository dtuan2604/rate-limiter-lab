package lab.ratelimiter.gateway.config;

import lab.ratelimiter.gateway.http.admin.AcceptancePolicyEventControlHandler;
import lab.ratelimiter.gateway.http.admin.AcceptancePolicyEventControlRoutes;
import lab.ratelimiter.gateway.policy.PolicyEventConsumer;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration(proxyBeanMethods = false)
@Profile("acceptance")
@ConditionalOnProperty(
    prefix = "rate-limiter.policy-control",
    name = {"enabled", "acceptance-controls-enabled"},
    havingValue = "true")
public class AcceptancePolicyEventControlConfiguration {

  @Bean
  AcceptancePolicyEventControlHandler acceptancePolicyEventControlHandler(
      PolicyEventConsumer eventConsumer) {
    return new AcceptancePolicyEventControlHandler(eventConsumer);
  }

  @Bean
  @Qualifier("acceptancePolicyEventControlRoutes")
  RouterFunction<ServerResponse> acceptancePolicyEventControlRoutes(
      AcceptancePolicyEventControlHandler handler) {
    return AcceptancePolicyEventControlRoutes.routes(handler);
  }
}
