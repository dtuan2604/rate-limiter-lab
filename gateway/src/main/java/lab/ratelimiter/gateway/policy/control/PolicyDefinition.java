package lab.ratelimiter.gateway.policy.control;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;
import lab.ratelimiter.gateway.application.FailureMode;

public record PolicyDefinition(
    String description,
    String routeId,
    String path,
    List<String> methods,
    List<PolicyIdentityComponent> identityComponents,
    PolicyAlgorithmDefinition algorithm,
    FailureMode failureMode,
    int priority) {

  private static final Pattern ROUTE_ID = Pattern.compile("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*");

  public PolicyDefinition {
    if (description != null && description.length() > 1024) {
      throw new IllegalArgumentException("description exceeds 1024 characters");
    }
    requireText(routeId, "route ID");
    if (!ROUTE_ID.matcher(routeId).matches()) {
      throw new IllegalArgumentException("route ID must be normalized");
    }
    requireNormalizedPath(path);
    methods = List.copyOf(Objects.requireNonNull(methods, "methods"));
    if (!methods.equals(List.of("GET"))) {
      throw new IllegalArgumentException("methods must contain only GET exactly once");
    }
    identityComponents =
        List.copyOf(Objects.requireNonNull(identityComponents, "identityComponents"));
    if (!identityComponents.equals(
        List.of(
            new PolicyIdentityComponent("HEADER", "X-Client-Id"),
            new PolicyIdentityComponent("ROUTE", null)))) {
      throw new IllegalArgumentException("identity must be HEADER:X-Client-Id followed by ROUTE");
    }
    Objects.requireNonNull(algorithm, "algorithm");
    Objects.requireNonNull(failureMode, "failureMode");
    if (priority < 0 || priority > 1000) {
      throw new IllegalArgumentException("priority must be between 0 and 1000");
    }
  }

  public PolicyDefinition(
      String description,
      String routeId,
      String path,
      List<String> methods,
      List<PolicyIdentityComponent> identityComponents,
      long limit,
      Duration window,
      FailureMode failureMode,
      int priority) {
    this(
        description,
        routeId,
        path,
        methods,
        identityComponents,
        new FixedWindowAlgorithmDefinition(limit, window),
        failureMode,
        priority);
  }

  public long limit() {
    return fixedWindow().limit();
  }

  public Duration window() {
    return fixedWindow().window();
  }

  private FixedWindowAlgorithmDefinition fixedWindow() {
    if (algorithm instanceof FixedWindowAlgorithmDefinition fixedWindow) {
      return fixedWindow;
    }
    throw new IllegalStateException("policy is not Fixed Window");
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  private static void requireNormalizedPath(String value) {
    requireText(value, "path");
    if (value.length() > 512 || value.getBytes(StandardCharsets.UTF_8).length > 512) {
      throw new IllegalArgumentException("path exceeds 512 bytes");
    }
    URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("path must be a normalized absolute proxy path");
    }
    if (!value.startsWith("/proxy/")
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !value.equals(uri.normalize().getPath())) {
      throw new IllegalArgumentException("path must be a normalized absolute proxy path");
    }
  }
}
