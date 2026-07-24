"""Tests for the Phase 0 command boundary."""

import pytest

from rate_limiter_traffic_simulator.__main__ import main


def test_cli_reports_version_without_generating_traffic(
    capsys: pytest.CaptureFixture[str],
) -> None:
    """The Phase 0 CLI identifies itself and performs no traffic work."""
    main()

    assert capsys.readouterr().out == "rate-limiter-traffic-simulator 0.0.0\n"
