# Slate v2 backend — Bun + Elysia + bun:sqlite.
# Build context is the REPO ROOT (see backend/docker-compose.yml):
#   docker build -f backend/Dockerfile .
FROM oven/bun:1.3.5 AS build
WORKDIR /app

# Install runtime dependencies first (cached unless the lockfile changes).
COPY backend/package.json backend/bun.lock ./
RUN bun install --frozen-lockfile --production

# Application source, numbered migrations, and the frozen JSON schemas.
COPY backend/src ./src
COPY backend/migrations ./migrations
COPY schema/slate.schema.json schema/interactions.schema.json ./schema/

FROM oven/bun:1.3.5-slim AS runtime
WORKDIR /app
ENV NODE_ENV=production \
    SLATE_PORT=3000

COPY --from=build --chown=bun:bun /app /app

# Volume for the SQLite database (SLATE_DB_PATH=/data/slate.db in compose).
RUN mkdir -p /data && chown -R bun:bun /data
USER bun
VOLUME /data
EXPOSE 3000

# Docker HEALTHCHECK hits the unauthenticated /health endpoint.
HEALTHCHECK --interval=30s --timeout=5s --start-period=10s --retries=3 \
  CMD bun -e "fetch('http://127.0.0.1:' + (process.env.SLATE_PORT || 3000) + '/health').then(r => process.exit(r.ok ? 0 : 1)).catch(() => process.exit(1))"

CMD ["bun", "run", "src/index.ts"]
