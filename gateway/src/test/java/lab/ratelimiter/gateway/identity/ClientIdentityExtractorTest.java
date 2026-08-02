package lab.ratelimiter.gateway.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ClientIdentityExtractorTest {

  private final ClientIdentityExtractor extractor = new ClientIdentityExtractor();

  @Test
  void missingOrBlankClientIdentityIsRejectedWithoutFallback() {
    assertThat(extractor.extract(null, "catalog.items")).isEmpty();
    assertThat(extractor.extract("", "catalog.items")).isEmpty();
    assertThat(extractor.extract("   ", "catalog.items")).isEmpty();
  }

  @Test
  void identityCombinesClientAndNormalizedRouteWithoutExposingRawClient() {
    LimiterIdentity first = extractor.extract("client-a", "catalog.items").orElseThrow();
    LimiterIdentity same = extractor.extract("client-a", "catalog.items").orElseThrow();
    LimiterIdentity otherClient = extractor.extract("client-b", "catalog.items").orElseThrow();
    LimiterIdentity otherRoute = extractor.extract("client-a", "catalog.details").orElseThrow();

    assertThat(first).isEqualTo(same);
    assertThat(first).isNotEqualTo(otherClient).isNotEqualTo(otherRoute);
    assertThat(first.digest()).hasSize(64).doesNotContain("client-a", "catalog.items");
  }

  @Test
  void lengthBoundariesPreventConcatenationCollisions() {
    LimiterIdentity first = extractor.extract("ab", "c").orElseThrow();
    LimiterIdentity second = extractor.extract("a", "bc").orElseThrow();

    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void overlongIdentityComponentsAreRejected() {
    assertThatThrownBy(() -> extractor.extract("x".repeat(257), "catalog.items"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("client ID");
    assertThatThrownBy(() -> extractor.extract("client-a", "x".repeat(129)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("route ID");
  }

  @Test
  void malformedDigestValueIsRejected() {
    assertThatThrownBy(() -> new LimiterIdentity("not-a-sha256-digest"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("SHA-256");
  }
}
