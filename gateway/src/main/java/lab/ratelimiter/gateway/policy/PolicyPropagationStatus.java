package lab.ratelimiter.gateway.policy;

import java.util.concurrent.atomic.AtomicBoolean;

public final class PolicyPropagationStatus {

  private final AtomicBoolean eventSubscriptionAvailable = new AtomicBoolean();

  public static PolicyPropagationStatus available() {
    PolicyPropagationStatus status = new PolicyPropagationStatus();
    status.markEventSubscriptionAvailable();
    return status;
  }

  public void markEventSubscriptionAvailable() {
    eventSubscriptionAvailable.set(true);
  }

  public void markEventSubscriptionUnavailable() {
    eventSubscriptionAvailable.set(false);
  }

  public boolean eventSubscriptionAvailable() {
    return eventSubscriptionAvailable.get();
  }
}
