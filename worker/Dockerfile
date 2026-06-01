# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Gradle wrapper first — cached until wrapper version changes
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
RUN chmod +x gradlew

# Build scripts only — cached until any build.gradle.kts changes
COPY settings.gradle.kts build.gradle.kts ./
COPY core/build.gradle.kts          core/build.gradle.kts
COPY server/build.gradle.kts         server/build.gradle.kts
COPY worker/build.gradle.kts         worker/build.gradle.kts
COPY app/shared/build.gradle.kts     app/shared/build.gradle.kts
COPY app/androidApp/build.gradle.kts app/androidApp/build.gradle.kts
COPY app/desktopApp/build.gradle.kts app/desktopApp/build.gradle.kts

# Warm dependency cache (fails silently if no network — still cached on re-build)
RUN ./gradlew :worker:dependencies --no-daemon -q 2>/dev/null || true

# Full source copy + build
COPY . .
RUN ./gradlew :worker:installDist --no-daemon

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Copy the full worker distribution (bin/ + lib/)
COPY --from=build /app/worker/build/install/worker/ .

# Create logs dir so the file appender doesn't error on startup
# (logs are ephemeral in Docker; Render captures stdout instead)
RUN mkdir -p logs

# JVM tuning: respect container memory limits, use 75% for heap
ENV JAVA_OPTS="-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["./bin/worker"]
