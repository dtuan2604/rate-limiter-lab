package lab.ratelimiter.gateway.http;

import static org.springframework.web.reactive.function.server.RequestPredicates.path;

import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;

public final class GatewayRoutes {

  private GatewayRoutes() {}

  public static RouterFunction<ServerResponse> routes(GatewayHttpHandler handler) {
    return RouterFunctions.route(path("/proxy/**"), handler::proxyCatalogItems);
  }
}
