FROM python:3.12.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY traffic-simulator/pyproject.toml ./
COPY traffic-simulator/README.md ./
COPY traffic-simulator/src ./src
RUN python -m pip install \
    --disable-pip-version-check \
    --no-cache-dir \
    --root-user-action=ignore \
    .

USER 65532:65532

ENTRYPOINT ["rate-limiter-traffic-simulator"]
