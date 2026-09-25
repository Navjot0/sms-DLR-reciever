import pytest
import requests

from dlr_helper import DLR_BASE_URL


@pytest.fixture(scope="session", autouse=True)
def dlr_receiver_up():
    """Fail fast (with a clear message) when the receiver or its database is down."""
    try:
        health = requests.get(f"{DLR_BASE_URL}/actuator/health", timeout=5).json()
    except requests.RequestException as exc:
        pytest.exit(f"DLR receiver not reachable at {DLR_BASE_URL}: {exc}", returncode=2)
    if health.get("status") != "UP":
        pytest.exit(f"DLR receiver unhealthy: {health}", returncode=2)
    return health
