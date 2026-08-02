package lab.ratelimiter.gateway.http;

import java.util.Objects;

record GatewayErrorResponse(int status, String error, String message, String correlationId) {

  GatewayErrorResponse {
    Objects.requireNonNull(error, "error");
    Objects.requireNonNull(message, "message");
    Objects.requireNonNull(correlationId, "correlationId");
  }
}
