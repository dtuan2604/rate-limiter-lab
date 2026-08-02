package lab.ratelimiter.gateway.proxy;

import reactor.core.publisher.Mono;

public interface CatalogBackendClient {

  Mono<CatalogBackendResponse> forward(CatalogBackendRequest request);

  Mono<Boolean> isHealthy();
}
