package lab.ratelimiter.gateway.config;

import java.time.Instant;
import lab.ratelimiter.gateway.policy.PolicySnapshot;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.policy.StaticPolicySnapshot;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
@ConditionalOnProperty(
    prefix = "rate-limiter.policy-control",
    name = "enabled",
    havingValue = "false",
    matchIfMissing = true)
public class StaticPolicyConfiguration {

  @Bean
  StaticPolicySnapshot staticPolicySnapshot(GatewayProperties properties) {
    return StaticPolicyCompiler.compile(properties);
  }

  @Bean
  PolicySnapshotStore policySnapshotStore(StaticPolicySnapshot snapshot) {
    return new PolicySnapshotStore(new PolicySnapshot(0, Instant.EPOCH, snapshot.policies()));
  }
}
