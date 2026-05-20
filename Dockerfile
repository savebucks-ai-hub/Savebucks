# ── Stage 1: Build ───────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk-jammy AS build
WORKDIR /app

# Copy Gradle wrapper first so it can be downloaded before source changes
COPY gradlew gradlew.bat ./
COPY gradle/ gradle/
RUN chmod +x gradlew

# Copy build scripts (cached layer — only invalidated when deps change)
COPY settings.gradle.kts build.gradle.kts ./
COPY core/build.gradle.kts core/build.gradle.kts
COPY server/build.gradle.kts server/build.gradle.kts
COPY worker/build.gradle.kts worker/build.gradle.kts
COPY app/shared/build.gradle.kts app/shared/build.gradle.kts
COPY app/androidApp/build.gradle.kts app/androidApp/build.gradle.kts
COPY app/desktopApp/build.gradle.kts app/desktopApp/build.gradle.kts

# Download Gradle distribution + warm dependency cache
RUN ./gradlew dependencies --no-daemon -q 2>/dev/null || true

# Copy source and build
COPY . .
RUN ./gradlew :server:buildFatJar --no-daemon

# ── Stage 2: Runtime ─────────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /app/server/build/libs/*-all.jar app.jar

# Render injects PORT; application.conf picks it up via ${?PORT}
EXPOSE 10000

ENTRYPOINT ["java", "-XX:+UseContainerSupport", "-XX:MaxRAMPercentage=75.0", "-jar", "app.jar"]
