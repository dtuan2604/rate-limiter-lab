package lab.ratelimiter.gateway.http.admin;

import java.util.Objects;
import lab.ratelimiter.gateway.policy.PolicyEventConsumer;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;

public final class AcceptancePolicyEventControlHandler {

  private final PolicyEventConsumer eventConsumer;

  public AcceptancePolicyEventControlHandler(PolicyEventConsumer eventConsumer) {
    this.eventConsumer = Objects.requireNonNull(eventConsumer, "eventConsumer");
  }

  public Mono<ServerResponse> pause(ServerRequest ignored) {
    eventConsumer.pause();
    return state();
  }

  public Mono<ServerResponse> resume(ServerRequest ignored) {
    eventConsumer.resume();
    return state();
  }

  private Mono<ServerResponse> state() {
    return ServerResponse.ok()
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(new EventConsumptionState(eventConsumer.paused()));
  }

  public record EventConsumptionState(boolean paused) {}
}
