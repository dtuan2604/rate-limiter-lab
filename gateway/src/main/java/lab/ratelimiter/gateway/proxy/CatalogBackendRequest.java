package lab.ratelimiter.gateway.proxy;

import java.util.Objects;

public record CatalogBackendRequest(
    String rawQuery, String clientId, String correlationId, String accept) {

  public CatalogBackendRequest {
    Objects.requireNonNull(clientId, "clientId");
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(accept, "accept");
  }
}
