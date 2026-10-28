# ---- web client ----
FROM node:22-alpine AS web
WORKDIR /web
COPY web/package.json web/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY web/ ./
# vite build alone does not type-check
RUN npm run check && npm run build

# ---- backend build ----
FROM gradle:9.8.0-jdk21 AS backend
WORKDIR /src
# only what the backend build reads, so the APK and installer of each run don't bust the layer
COPY settings.gradle.kts build.gradle.kts ./
COPY gradle/ gradle/
COPY shared/ shared/
COPY backend/ backend/
RUN gradle :backend:installDist --no-daemon --console=plain

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app closeby \
    && mkdir -p /data /app/apk \
    && chown closeby /data
COPY --from=backend /src/backend/build/install/backend /app
COPY --from=web /web/dist /app/web
# signed APK + version.json, put there by CI (empty in local builds)
COPY docker/apk/ /app/apk/
# Windows installer of the Tauri shell, put there by CI (empty in local builds)
COPY docker/desktop/ /app/desktop/

ENV DATABASE_PATH=/data/closeby.db \
    CACHE_DIR=/data/cache \
    APK_DIR=/app/apk \
    DESKTOP_DIR=/app/desktop \
    WEB_DIR=/app/web
USER closeby
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]
