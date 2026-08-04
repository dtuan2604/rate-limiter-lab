package lab.ratelimiter.gateway.http.admin;

import java.util.List;

public record AdminErrorResponse(
    int status, String error, String message, String correlationId, List<Violation> violations) {

  public AdminErrorResponse {
    violations = List.copyOf(violations);
  }

  public record Violation(String field, String code, String message) {}
}
