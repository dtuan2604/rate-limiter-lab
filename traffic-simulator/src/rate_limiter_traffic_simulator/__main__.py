"""Phase 0 command-line entry point."""

from rate_limiter_traffic_simulator import __version__


def main() -> None:
    """Report the scaffold version without generating traffic."""
    print(f"rate-limiter-traffic-simulator {__version__}")


if __name__ == "__main__":
    main()
