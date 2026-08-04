package lab.ratelimiter.gateway.config;

import java.time.Clock;
import lab.ratelimiter.gateway.http.admin.AdminAuthenticationFilter;
import lab.ratelimiter.gateway.http.admin.AdminPolicyHandler;
import lab.ratelimiter.gateway.http.admin.AdminPolicyRoutes;
import lab.ratelimiter.gateway.http.admin.InternalPolicyRoutes;
import lab.ratelimiter.gateway.http.admin.PolicySnapshotEndpointHandler;
import lab.ratelimiter.gateway.policy.PolicyControlScheduler;
import lab.ratelimiter.gateway.policy.PolicyEventCodec;
import lab.ratelimiter.gateway.policy.PolicyEventConsumer;
import lab.ratelimiter.gateway.policy.PolicyEventPublisher;
import lab.ratelimiter.gateway.policy.PolicyOutboxDispatcher;
import lab.ratelimiter.gateway.policy.PolicyPropagationStatus;
import lab.ratelimiter.gateway.policy.PolicyReconciler;
import lab.ratelimiter.gateway.policy.PolicySnapshotCompiler;
import lab.ratelimiter.gateway.policy.PolicySnapshotRefreshCoordinator;
import lab.ratelimiter.gateway.policy.PolicySnapshotStore;
import lab.ratelimiter.gateway.policy.RedisPolicyEventPublisher;
import lab.ratelimiter.gateway.policy.RedisPolicyEventSubscriber;
import lab.ratelimiter.gateway.policy.persistence.PostgresPolicyRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.data.redis.connection.ReactiveRedisConnectionFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.listener.ReactiveRedisMessageListenerContainer;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({PolicyControlProperties.class, GatewayProperties.class})
@ConditionalOnProperty(
    prefix = "rate-limiter.policy-control",
    name = "enabled",
    havingValue = "true")
public class PolicyControlConfiguration {

  @Bean
  PostgresPolicyRepository postgresPolicyRepository(
      DatabaseClient database, ReactiveTransactionManager transactionManager, Clock gatewayClock) {
    return new PostgresPolicyRepository(
        database, TransactionalOperator.create(transactionManager), gatewayClock);
  }

  @Bean
  PolicySnapshotCompiler policySnapshotCompiler() {
    return new PolicySnapshotCompiler();
  }

  @Bean
  @DependsOn("flywayInitializer")
  PolicySnapshotStore policySnapshotStore(
      PostgresPolicyRepository repository,
      PolicySnapshotCompiler compiler,
      Clock gatewayClock,
      PolicyControlProperties properties) {
    var activeSet = repository.loadActiveSet().block(properties.databaseTimeout());
    if (activeSet == null) {
      throw new IllegalStateException("PostgreSQL did not provide the initial policy snapshot");
    }
    return new PolicySnapshotStore(compiler.compile(activeSet, gatewayClock));
  }

  @Bean
  PolicySnapshotRefreshCoordinator policySnapshotRefreshCoordinator(
      PostgresPolicyRepository repository,
      PolicySnapshotCompiler compiler,
      PolicySnapshotStore store,
      Clock gatewayClock) {
    return new PolicySnapshotRefreshCoordinator(repository, compiler, store, gatewayClock);
  }

  @Bean
  PolicyEventCodec policyEventCodec(PolicyControlProperties properties) {
    return new PolicyEventCodec(properties.maximumEventBytes());
  }

  @Bean
  PolicyEventConsumer policyEventConsumer(
      PolicyEventCodec codec, PolicySnapshotRefreshCoordinator coordinator, Clock gatewayClock) {
    return new PolicyEventConsumer(codec, coordinator, gatewayClock);
  }

  @Bean
  ReactiveRedisMessageListenerContainer policyRedisListenerContainer(
      ReactiveRedisConnectionFactory connectionFactory) {
    return new ReactiveRedisMessageListenerContainer(connectionFactory);
  }

  @Bean
  PolicyPropagationStatus policyPropagationStatus() {
    return new PolicyPropagationStatus();
  }

  @Bean
  PolicyEventPublisher policyEventPublisher(
      ReactiveStringRedisTemplate redis, PolicyControlProperties properties) {
    return new RedisPolicyEventPublisher(redis, properties.eventChannel());
  }

  @Bean
  RedisPolicyEventSubscriber redisPolicyEventSubscriber(
      ReactiveRedisMessageListenerContainer container,
      PolicyControlProperties properties,
      PolicyEventConsumer consumer,
      PolicyPropagationStatus propagationStatus) {
    return new RedisPolicyEventSubscriber(
        container, properties.eventChannel(), consumer, propagationStatus);
  }

  @Bean
  PolicyOutboxDispatcher policyOutboxDispatcher(
      PostgresPolicyRepository repository,
      PolicyEventPublisher publisher,
      PolicyEventCodec codec,
      GatewayProperties gateway,
      PolicyControlProperties properties,
      Clock gatewayClock) {
    return new PolicyOutboxDispatcher(
        repository,
        publisher,
        codec,
        gateway.instanceId(),
        properties.outboxLeaseDuration(),
        gatewayClock);
  }

  @Bean
  PolicyReconciler policyReconciler(
      PostgresPolicyRepository repository,
      PolicySnapshotRefreshCoordinator coordinator,
      PolicySnapshotStore store,
      Clock gatewayClock) {
    return new PolicyReconciler(repository, coordinator, store, gatewayClock);
  }

  @Bean
  PolicyControlScheduler policyControlScheduler(
      PolicyOutboxDispatcher dispatcher,
      PolicyReconciler reconciler,
      PolicyControlProperties properties,
      GatewayProperties gateway) {
    return new PolicyControlScheduler(
        dispatcher,
        reconciler,
        properties.outboxPollInterval(),
        properties.reconciliationInterval(),
        PolicyControlScheduler.boundedInitialDelay(
            properties.reconciliationInterval(), gateway.instanceId()));
  }

  @Bean
  AdminAuthenticationFilter adminAuthenticationFilter(PolicyControlProperties properties) {
    return new AdminAuthenticationFilter(properties.adminBearerToken());
  }

  @Bean
  AdminPolicyHandler adminPolicyHandler(
      PostgresPolicyRepository repository,
      PolicySnapshotStore store,
      PolicyControlProperties properties) {
    return new AdminPolicyHandler(repository, properties.adminActor(), store);
  }

  @Bean
  @Qualifier("adminPolicyRoutes")
  RouterFunction<ServerResponse> adminPolicyRoutes(AdminPolicyHandler handler) {
    return AdminPolicyRoutes.routes(handler);
  }

  @Bean
  PolicySnapshotEndpointHandler policySnapshotEndpointHandler(
      PolicySnapshotStore store,
      PolicyReconciler reconciler,
      PolicyEventConsumer eventConsumer,
      PolicyPropagationStatus propagationStatus,
      GatewayProperties gateway,
      PolicyControlProperties properties) {
    return new PolicySnapshotEndpointHandler(
        store,
        reconciler,
        eventConsumer,
        propagationStatus,
        gateway.instanceId(),
        properties.acceptanceControlsEnabled());
  }

  @Bean
  @Qualifier("internalPolicyRoutes")
  RouterFunction<ServerResponse> internalPolicyRoutes(PolicySnapshotEndpointHandler handler) {
    return InternalPolicyRoutes.routes(handler);
  }
}
