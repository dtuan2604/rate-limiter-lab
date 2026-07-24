FROM python:3.12.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

WORKDIR /app
COPY mock-services/pyproject.toml ./
COPY mock-services/README.md ./
COPY mock-services/requirements.lock ./
COPY mock-services/src ./src
RUN python -m pip install \
    --disable-pip-version-check \
    --no-cache-dir \
    --root-user-action=ignore \
    --requirement requirements.lock \
    && python -m pip install \
    --disable-pip-version-check \
    --no-cache-dir \
    --no-deps \
    --root-user-action=ignore \
    .

USER 65532:65532
EXPOSE 8000

ENTRYPOINT ["python", "-m", "uvicorn"]
CMD ["rate_limiter_mock_services.catalog:app", "--host", "0.0.0.0", "--port", "8000"]
