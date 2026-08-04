package lab.ratelimiter.gateway.policy.control;

import java.util.Objects;

public record PolicyIdentityComponent(String type, String name) {

  public PolicyIdentityComponent {
    Objects.requireNonNull(type, "type");
    if (!(type.equals("HEADER") || type.equals("ROUTE"))) {
      throw new IllegalArgumentException("unsupported identity component: " + type);
    }
    if (type.equals("HEADER") && !"X-Client-Id".equals(name)) {
      throw new IllegalArgumentException("HEADER identity must use X-Client-Id");
    }
    if (type.equals("ROUTE") && name != null) {
      throw new IllegalArgumentException("ROUTE identity must not have a name");
    }
  }
}
