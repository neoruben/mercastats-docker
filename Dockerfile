# syntax=docker/dockerfile:1

# ==============================================================================
# Stage 1: Build (se ejecuta en la arquitectura del host para evitar emulación
# lenta de Gradle bajo QEMU; el bytecode JVM y sqlite-jdbc son multi-arch)
# ==============================================================================
# La versión de Gradle la fija el wrapper (gradle/wrapper/gradle-wrapper.properties).
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk AS builder

WORKDIR /home/gradle/project

COPY gradlew settings.gradle.kts build.gradle.kts gradle.properties ./
COPY gradle ./gradle
COPY core ./core
COPY server ./server

RUN ./gradlew :server:installDist --no-daemon

# ==============================================================================
# Stage 2: Runtime ligero (JRE 21 Alpine, usuario no-root, volumen /data)
# ==============================================================================
FROM eclipse-temurin:21-jre-alpine AS runtime

RUN apk add --no-cache tzdata \
    && addgroup -g 10001 -S mercastats \
    && adduser -u 10001 -S -G mercastats -h /app mercastats \
    && mkdir -p /data/inbox /app \
    && chown -R mercastats:mercastats /data /app

WORKDIR /app

COPY --from=builder --chown=mercastats:mercastats /home/gradle/project/server/build/install/server /app

USER mercastats

ENV HOST=0.0.0.0 \
    PORT=8080 \
    DATA_DIR=/data \
    TZ=Europe/Madrid

VOLUME ["/data"]
EXPOSE 8080

HEALTHCHECK --interval=30s --timeout=5s --start-period=20s --retries=3 \
    CMD wget -q --spider "http://127.0.0.1:${PORT:-8080}/api/health" || exit 1

ENTRYPOINT ["/app/bin/server"]
