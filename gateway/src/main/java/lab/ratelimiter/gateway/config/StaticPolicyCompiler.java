package lab.ratelimiter.gateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import lab.ratelimiter.gateway.config.GatewayProperties.PolicyProperties;
import lab.ratelimiter.gateway.domain.limiter.FixedWindowPolicy;
import lab.ratelimiter.gateway.domain.limiter.PolicyId;
import lab.ratelimiter.gateway.domain.limiter.PolicyVersion;
import lab.ratelimiter.gateway.policy.CompiledPolicy;
import lab.ratelimiter.gateway.policy.StaticPolicySnapshot;

final class StaticPolicyCompiler {

  private static final Pattern ROUTE_ID = Pattern.compile("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9]*)*");

  private StaticPolicyCompiler() {}

  static StaticPolicySnapshot compile(GatewayProperties properties) {
    requireValidBackendUri(properties.catalogBaseUrl());
    requirePositiveDuration(properties.backendTimeout(), "backend timeout");
    if (properties.policies().isEmpty()) {
      throw invalid("at least one static policy is required");
    }

    List<CompiledPolicy> compiled =
        properties.policies().stream().map(StaticPolicyCompiler::compile).toList();
    Set<String> routes = new HashSet<>();
    for (CompiledPolicy policy : compiled) {
      String routeKey = policy.method() + " " + policy.path();
      if (!routes.add(routeKey)) {
        throw new IllegalArgumentException("duplicate static route: " + routeKey);
      }
    }
    return new StaticPolicySnapshot(compiled);
  }

  private static CompiledPolicy compile(PolicyProperties external) {
    String id = requireText(external.id(), "policy ID");
    long version = requirePositive(external.version(), "policy version");
    String routeId = requireText(external.routeId(), "route ID");
    if (!ROUTE_ID.matcher(routeId).matches()) {
      throw invalid("route ID must be normalized");
    }
    String path = requireValidPath(external.path());
    String method = requireText(external.method(), "method");
    if (!method.equals("GET")) {
      throw invalid("only GET static routes are supported");
    }
    String algorithm = requireText(external.algorithm(), "algorithm");
    if (!algorithm.equals("FIXED_WINDOW")) {
      throw invalid("unsupported algorithm: " + algorithm);
    }
    long limit = requirePositive(external.limit(), "limit");
    Duration window = requirePositiveDuration(external.window(), "window");
    FixedWindowPolicy policy =
        new FixedWindowPolicy(new PolicyId(id), new PolicyVersion(version), limit, window);
    return new CompiledPolicy(routeId, path, method, policy);
  }

  private static void requireValidBackendUri(URI uri) {
    if (uri == null
        || !uri.isAbsolute()
        || uri.getHost() == null
        || !(uri.getScheme().equals("http") || uri.getScheme().equals("https"))
        || uri.getUserInfo() != null
        || uri.getQuery() != null
        || uri.getFragment() != null) {
      throw invalid("catalog backend URI must be an absolute HTTP(S) URI");
    }
  }

  private static String requireValidPath(String value) {
    String path = requireText(value, "route path");
    URI uri;
    try {
      uri = URI.create(path);
    } catch (IllegalArgumentException exception) {
      throw invalid("route path must be a normalized absolute proxy path");
    }
    if (!path.startsWith("/proxy/")
        || uri.getQuery() != null
        || uri.getFragment() != null
        || !path.equals(uri.normalize().getPath())) {
      throw invalid("route path must be a normalized absolute proxy path");
    }
    return path;
  }

  private static String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw invalid(name + " is required");
    }
    return value;
  }

  private static long requirePositive(Long value, String name) {
    if (value == null || value <= 0) {
      throw invalid(name + " must be positive");
    }
    return value;
  }

  private static Duration requirePositiveDuration(Duration value, String name) {
    if (value == null
        || value.isZero()
        || value.isNegative()
        || !value.equals(Duration.ofMillis(value.toMillis()))) {
      throw invalid(name + " must be a positive whole-millisecond duration");
    }
    return value;
  }

  private static IllegalArgumentException invalid(String detail) {
    return new IllegalArgumentException("invalid static policy configuration: " + detail);
  }
}
