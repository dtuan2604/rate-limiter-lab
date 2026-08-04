package lab.ratelimiter.gateway.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class PolicyControlPropertiesTest {

  @Test
  void enabledControlPlaneRequiresASecretToken() {
    assertThatThrownBy(() -> properties(true, null, Duration.ofSeconds(1), 4096))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("ADMIN_BEARER_TOKEN");
    assertThatThrownBy(() -> properties(true, " ", Duration.ofSeconds(1), 4096))
        .isInstanceOf(IllegalArgumentException.class);
    properties(false, null, Duration.ofSeconds(1), 4096);
  }

  @Test
  void durationsAndEventSizeAreBounded() {
    for (Duration invalid : new Duration[] {null, Duration.ZERO, Duration.ofSeconds(-1)}) {
      assertThatThrownBy(() -> properties(true, "token", invalid, 4096))
          .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
    }
    assertThatThrownBy(() -> properties(true, "token", Duration.ofSeconds(1), 255))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> properties(true, "token", Duration.ofSeconds(1), 65_537))
        .isInstanceOf(IllegalArgumentException.class);
    properties(true, "token", Duration.ofMillis(1), 256);
    properties(true, "token", Duration.ofSeconds(1), 65_536);
  }

  @Test
  void actorAndChannelAreRequired() {
    assertThatThrownBy(
            () ->
                new PolicyControlProperties(
                    true,
                    "token",
                    null,
                    "channel",
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    4096,
                    false))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(
            () ->
                new PolicyControlProperties(
                    true,
                    "token",
                    "admin",
                    null,
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(1),
                    4096,
                    false))
        .isInstanceOf(NullPointerException.class);
  }

  private static PolicyControlProperties properties(
      boolean enabled, String token, Duration duration, int maximumBytes) {
    return new PolicyControlProperties(
        enabled,
        token,
        "local-admin",
        "policy-events",
        duration,
        duration,
        duration,
        duration,
        maximumBytes,
        false);
  }
}
