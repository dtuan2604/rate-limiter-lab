package lab.ratelimiter.gateway.proxy;

import java.util.Objects;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;

public record CatalogBackendResponse(HttpStatusCode status, MediaType contentType, String body) {

  public CatalogBackendResponse {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(contentType, "contentType");
    Objects.requireNonNull(body, "body");
  }
}
