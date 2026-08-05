package lab.ratelimiter.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import lab.ratelimiter.gateway.http.admin.AcceptancePolicyEventControlHandler;
import lab.ratelimiter.gateway.policy.PolicyEventConsumer;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.ServerResponse;

class AcceptancePolicyEventControlConfigurationTest {

  private final PolicyEventConsumer consumer = mock(PolicyEventConsumer.class);

  @Test
  void normalDevelopmentAndProductionProfilesExposeNoControlsEvenWhenEnabled() {
    assertControlsAbsent(contextWithProfile(null, true));
    assertControlsAbsent(contextWithProfile("development", true));
    assertControlsAbsent(contextWithProfile("production", true));
  }

  @Test
  void acceptanceProfileWithoutExplicitPropertyExposesNoControls() {
    assertControlsAbsent(contextWithProfile("acceptance", false));
  }

  @Test
  void acceptanceProfileAndExplicitPropertyRegisterWorkingControls() {
    contextWithProfile("acceptance", true)
        .run(
            context -> {
              assertThat(context).hasSingleBean(AcceptancePolicyEventControlHandler.class);
              @SuppressWarnings("unchecked")
              RouterFunction<ServerResponse> routes =
                  (RouterFunction<ServerResponse>)
                      context.getBean("acceptancePolicyEventControlRoutes");
              WebTestClient client = WebTestClient.bindToRouterFunction(routes).build();

              client.post().uri("/internal/policy-events/pause").exchange().expectStatus().isOk();
              verify(consumer).pause();
              client.post().uri("/internal/policy-events/resume").exchange().expectStatus().isOk();
              verify(consumer).resume();
            });
  }

  private ApplicationContextRunner contextWithProfile(String profile, boolean enabled) {
    ApplicationContextRunner runner =
        new ApplicationContextRunner()
            .withUserConfiguration(AcceptancePolicyEventControlConfiguration.class)
            .withBean(PolicyEventConsumer.class, () -> consumer);
    if (profile != null) {
      runner = runner.withPropertyValues("spring.profiles.active=" + profile);
    }
    return enabled
        ? runner.withPropertyValues(
            "rate-limiter.policy-control.enabled=true",
            "rate-limiter.policy-control.acceptance-controls-enabled=true")
        : runner;
  }

  private static void assertControlsAbsent(ApplicationContextRunner runner) {
    runner.run(
        context -> {
          assertThat(context).doesNotHaveBean(AcceptancePolicyEventControlHandler.class);
          assertThat(context).doesNotHaveBean("acceptancePolicyEventControlRoutes");
        });
  }
}
