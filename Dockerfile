# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM gradle:8.5-jdk21 AS build
WORKDIR /app

# Copy gradle wrapper + version catalog first (better layer caching)
COPY gradle/ gradle/
COPY gradlew gradlew.bat settings.gradle.kts build.gradle.kts ./
COPY core/build.gradle.kts core/build.gradle.kts
COPY server/build.gradle.kts server/build.gradle.kts
COPY worker/build.gradle.kts worker/build.gradle.kts
COPY app/shared/build.gradle.kts app/shared/build.gradle.kts
COPY app/androidApp/build.gradle.kts app/androidApp/build.gradle.kts
COPY app/desktopApp/build.gradle.kts app/desktopApp/build.gradle.kts

# Warm up the dependency cache (will be reused on subsequent builds)
RUN gradle dependencies --no-daemon --configuration runtimeClasspath -q 2>/dev/null || true

# Copy full source
COPY . .

# Build fat JAR (shadow jar with all dependencies bundled)
RUN gradle :server:buildFatJar --no-daemon

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app

COPY --from=build /app/server/build/libs/*-all.jar app.jar

# Render injects PORT at runtime; application.conf picks it up via ${?PORT}
EXPOSE 10000

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
