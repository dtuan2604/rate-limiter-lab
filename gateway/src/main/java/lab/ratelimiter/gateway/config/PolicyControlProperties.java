package lab.ratelimiter.gateway.config;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("rate-limiter.policy-control")
public record PolicyControlProperties(
    boolean enabled,
    String adminBearerToken,
    String adminActor,
    String eventChannel,
    Duration databaseTimeout,
    Duration outboxPollInterval,
    Duration outboxLeaseDuration,
    Duration reconciliationInterval,
    int maximumEventBytes,
    boolean acceptanceControlsEnabled) {

  public PolicyControlProperties {
    if (enabled && (adminBearerToken == null || adminBearerToken.isBlank())) {
      throw new IllegalArgumentException("ADMIN_BEARER_TOKEN is required");
    }
    Objects.requireNonNull(adminActor, "adminActor");
    Objects.requireNonNull(eventChannel, "eventChannel");
    requirePositive(databaseTimeout, "databaseTimeout");
    requirePositive(outboxPollInterval, "outboxPollInterval");
    requirePositive(outboxLeaseDuration, "outboxLeaseDuration");
    requirePositive(reconciliationInterval, "reconciliationInterval");
    if (maximumEventBytes < 256 || maximumEventBytes > 65_536) {
      throw new IllegalArgumentException("maximumEventBytes must be 256..65536");
    }
  }

  private static void requirePositive(Duration value, String name) {
    Objects.requireNonNull(value, name);
    if (value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(name + " must be positive");
    }
  }
}
