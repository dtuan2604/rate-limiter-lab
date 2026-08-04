package lab.ratelimiter.gateway.http.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Objects;
import java.util.UUID;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

public final class AdminAuthenticationFilter implements WebFilter {

  private final byte[] expectedToken;

  public AdminAuthenticationFilter(String expectedToken) {
    if (expectedToken == null || expectedToken.isBlank()) {
      throw new IllegalArgumentException("admin bearer token is required");
    }
    this.expectedToken = expectedToken.getBytes(StandardCharsets.UTF_8);
  }

  @Override
  public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
    String path = exchange.getRequest().getPath().pathWithinApplication().value();
    if (!(path.startsWith("/admin/api/v1/") || path.startsWith("/internal/"))) {
      return chain.filter(exchange);
    }
    String authorization = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
    byte[] supplied =
        authorization != null && authorization.startsWith("Bearer ")
            ? authorization.substring("Bearer ".length()).getBytes(StandardCharsets.UTF_8)
            : new byte[0];
    if (MessageDigest.isEqual(expectedToken, supplied)) {
      return chain.filter(exchange);
    }
    return unauthorized(exchange);
  }

  private static Mono<Void> unauthorized(ServerWebExchange exchange) {
    String correlationId =
        Objects.requireNonNullElseGet(
            exchange.getRequest().getHeaders().getFirst("X-Correlation-Id"),
            () -> UUID.randomUUID().toString());
    String json =
        "{\"status\":401,\"error\":\"ADMIN_AUTHENTICATION_REQUIRED\","
            + "\"message\":\"Administrative bearer token is required\","
            + "\"correlationId\":\""
            + jsonEscape(correlationId)
            + "\",\"violations\":[]}";
    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
    exchange.getResponse().getHeaders().set(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
    exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
    DataBuffer body =
        exchange.getResponse().bufferFactory().wrap(json.getBytes(StandardCharsets.UTF_8));
    return exchange.getResponse().writeWith(Mono.just(body));
  }

  private static String jsonEscape(String value) {
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }
}
