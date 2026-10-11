# ANZEN service + Shonko web app, one origin. Build: docker build -t anzen .
FROM python:3.12-slim
COPY --from=ghcr.io/astral-sh/uv:0.8 /uv /usr/local/bin/uv
WORKDIR /app
COPY backend/pyproject.toml backend/uv.lock backend/
RUN cd backend && uv sync --frozen --no-dev --no-install-project
COPY backend/ backend/
COPY web/ web/
COPY contracts/deployments/ contracts/deployments/
ENV PYTHONUNBUFFERED=1 ANZEN_DB=/data/anzen.db
WORKDIR /app/backend
CMD ["uv", "run", "--no-dev", "python", "-m", "anzen.app"]
