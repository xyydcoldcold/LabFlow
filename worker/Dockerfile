FROM python:3.13-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    ARTIFACT_ROOT=/var/lib/labflow/artifacts \
    PIP_ROOT_USER_ACTION=ignore

RUN groupadd --gid 10001 labflow \
    && useradd \
        --uid 10001 \
        --gid labflow \
        --home-dir /app \
        --no-create-home \
        --shell /usr/sbin/nologin \
        labflow \
    && mkdir -p /var/lib/labflow/artifacts \
    && chown -R labflow:labflow /var/lib/labflow

WORKDIR /app

COPY pyproject.toml pyproject.toml
COPY src src

RUN python -m pip install --no-cache-dir .

USER labflow

CMD ["python", "-m", "labflow_worker"]
