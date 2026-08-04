package lab.ratelimiter.gateway.policy.control;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import lab.ratelimiter.gateway.application.FailureMode;
import org.junit.jupiter.api.Test;

class PolicyValidationTest {

  private static final List<String> GET = List.of("GET");
  private static final List<PolicyIdentityComponent> IDENTITY =
      List.of(
          new PolicyIdentityComponent("HEADER", "X-Client-Id"),
          new PolicyIdentityComponent("ROUTE", null));

  @Test
  void acceptsEveryBoundaryValue() {
    definition("a", "/proxy/a", GET, IDENTITY, 1, Duration.ofMillis(1), 0);
    definition("a.b9", "/proxy/catalog/items", GET, IDENTITY, 1_000_000, Duration.ofDays(1), 1000);
  }

  @Test
  void rejectsInvalidRouteIds() {
    for (String routeId : List.of("", "Catalog.Items", ".catalog", "catalog-", "catalog..items")) {
      assertInvalid(
          () -> definition(routeId, "/proxy/a", GET, IDENTITY, 1, Duration.ofSeconds(1), 1));
    }
  }

  @Test
  void rejectsInvalidPaths() {
    for (String path :
        List.of("", "/catalog/items", "proxy/catalog", "/proxy/a/../b", "/proxy/a?q=1")) {
      assertInvalid(
          () -> definition("catalog.items", path, GET, IDENTITY, 1, Duration.ofSeconds(1), 1));
    }
  }

  @Test
  void rejectsUnsupportedEmptyOrDuplicateMethods() {
    for (List<String> methods :
        List.of(List.<String>of(), List.of("POST"), List.of("GET", "GET"), List.of("get"))) {
      assertInvalid(
          () ->
              definition(
                  "catalog.items", "/proxy/a", methods, IDENTITY, 1, Duration.ofSeconds(1), 1));
    }
  }

  @Test
  void rejectsMissingUnsupportedReorderedOrDuplicateIdentityComponents() {
    var header = new PolicyIdentityComponent("HEADER", "X-Client-Id");
    var route = new PolicyIdentityComponent("ROUTE", null);
    for (List<PolicyIdentityComponent> components :
        List.of(
            List.<PolicyIdentityComponent>of(),
            List.of(header),
            List.of(route, header),
            List.of(header, header))) {
      assertInvalid(
          () ->
              definition(
                  "catalog.items", "/proxy/a", GET, components, 1, Duration.ofSeconds(1), 1));
    }
  }

  @Test
  void rejectsLimitsWindowsPrioritiesAndDescriptionsOutsideBounds() {
    assertInvalid(() -> definition("a", "/proxy/a", GET, IDENTITY, 0, Duration.ofSeconds(1), 1));
    assertInvalid(
        () -> definition("a", "/proxy/a", GET, IDENTITY, 1_000_001, Duration.ofSeconds(1), 1));
    assertInvalid(() -> definition("a", "/proxy/a", GET, IDENTITY, 1, Duration.ZERO, 1));
    assertInvalid(
        () -> definition("a", "/proxy/a", GET, IDENTITY, 1, Duration.ofDays(1).plusMillis(1), 1));
    assertInvalid(() -> definition("a", "/proxy/a", GET, IDENTITY, 1, Duration.ofNanos(1), 1));
    assertInvalid(() -> definition("a", "/proxy/a", GET, IDENTITY, 1, Duration.ofSeconds(1), -1));
    assertInvalid(() -> definition("a", "/proxy/a", GET, IDENTITY, 1, Duration.ofSeconds(1), 1001));
    assertThatThrownBy(
            () ->
                new PolicyDefinition(
                    "x".repeat(1025),
                    "a",
                    "/proxy/a",
                    GET,
                    IDENTITY,
                    1,
                    Duration.ofSeconds(1),
                    FailureMode.FAIL_CLOSED,
                    1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsNullRequiredValuesAndInvalidIdentityShapes() {
    assertInvalid(
        () ->
            new PolicyDefinition(
                null,
                null,
                "/proxy/a",
                GET,
                IDENTITY,
                1,
                Duration.ofSeconds(1),
                FailureMode.FAIL_CLOSED,
                1));
    assertInvalid(
        () ->
            new PolicyDefinition(
                null, "a", "/proxy/a", null, IDENTITY, 1, Duration.ofSeconds(1), null, 1));
    assertInvalid(() -> new PolicyIdentityComponent("COOKIE", null));
    assertInvalid(() -> new PolicyIdentityComponent("HEADER", "Other"));
    assertInvalid(() -> new PolicyIdentityComponent("ROUTE", "unexpected"));
  }

  private static PolicyDefinition definition(
      String routeId,
      String path,
      List<String> methods,
      List<PolicyIdentityComponent> identity,
      long limit,
      Duration window,
      int priority) {
    return new PolicyDefinition(
        null, routeId, path, methods, identity, limit, window, FailureMode.FAIL_CLOSED, priority);
  }

  private static void assertInvalid(Runnable constructor) {
    assertThatThrownBy(constructor::run)
        .isInstanceOfAny(IllegalArgumentException.class, NullPointerException.class);
  }
}
