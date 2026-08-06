package lab.ratelimiter.gateway.policy.control;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record WindowDuration(long amount, Unit unit) {

  private static final Pattern LITERAL = Pattern.compile("([1-9][0-9]*)(ms|s|m|h|d)");
  public static final long MAXIMUM_MILLISECONDS = 86_400_000;

  public WindowDuration {
    if (amount < 1) {
      throw new IllegalArgumentException("window amount must be positive");
    }
    Objects.requireNonNull(unit, "unit");
    long milliseconds;
    try {
      milliseconds = Math.multiplyExact(amount, unit.milliseconds());
    } catch (ArithmeticException exception) {
      throw new IllegalArgumentException("window exceeds 86400000 milliseconds", exception);
    }
    if (milliseconds > MAXIMUM_MILLISECONDS) {
      throw new IllegalArgumentException("window must not exceed 86400000 milliseconds");
    }
  }

  public static WindowDuration parse(String literal) {
    if (literal == null) {
      throw new IllegalArgumentException("window is required");
    }
    Matcher matcher = LITERAL.matcher(literal);
    if (!matcher.matches()) {
      throw new IllegalArgumentException("window must be a positive integer plus ms|s|m|h|d");
    }
    try {
      return new WindowDuration(
          Long.parseLong(matcher.group(1)), Unit.fromSymbol(matcher.group(2)));
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("window amount is too large", exception);
    }
  }

  public long toMilliseconds() {
    return Math.multiplyExact(amount, unit.milliseconds());
  }

  @Override
  public String toString() {
    return Long.toString(amount) + unit.symbol();
  }

  public enum Unit {
    MILLISECONDS("ms", 1),
    SECONDS("s", 1_000),
    MINUTES("m", 60_000),
    HOURS("h", 3_600_000),
    DAYS("d", 86_400_000);

    private final String symbol;
    private final long milliseconds;

    Unit(String symbol, long milliseconds) {
      this.symbol = symbol;
      this.milliseconds = milliseconds;
    }

    public String symbol() {
      return symbol;
    }

    public long milliseconds() {
      return milliseconds;
    }

    static Unit fromSymbol(String symbol) {
      for (Unit value : values()) {
        if (value.symbol.equals(symbol)) {
          return value;
        }
      }
      throw new IllegalArgumentException("unsupported window unit");
    }
  }
}
