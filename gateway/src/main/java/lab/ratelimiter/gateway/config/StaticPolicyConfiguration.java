package lab.ratelimiter.gateway.config;

import lab.ratelimiter.gateway.policy.StaticPolicySnapshot;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class StaticPolicyConfiguration {

  @Bean
  StaticPolicySnapshot staticPolicySnapshot(GatewayProperties properties) {
    return StaticPolicyCompiler.compile(properties);
  }
}
