# ---- backend build ----
FROM gradle:9.8.0-jdk21 AS backend
WORKDIR /src
COPY . .
RUN gradle :backend:installDist --no-daemon --console=plain

# ---- runtime ----
FROM eclipse-temurin:21-jre
RUN useradd --system --uid 10001 --home-dir /app closeby \
    && mkdir -p /data /app/apk \
    && chown closeby /data
COPY --from=backend /src/backend/build/install/backend /app
# signed APK + version.json, put there by CI (empty in local builds)
COPY docker/apk/ /app/apk/

ENV DATABASE_PATH=/data/closeby.db \
    CACHE_DIR=/data/cache \
    APK_DIR=/app/apk \
    WEB_DIR=/app/web
USER closeby
VOLUME /data
EXPOSE 8080
ENTRYPOINT ["/app/bin/backend"]
