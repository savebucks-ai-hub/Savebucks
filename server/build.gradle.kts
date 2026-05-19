plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinSerialization)
}

group = "com.savebucks"
version = "1.0.0"

application {
    mainClass = "com.savebucks.ApplicationKt"
}


dependencies {
    // Core shared module (data models)
    api(projects.core)

    // --- Ktor Server ---
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.compression)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.server.default.headers)
    implementation(libs.ktor.server.partial.content)
    implementation(libs.ktor.serialization.kotlinx.json)

    // --- Ktor Client (for Supabase REST + OpenAI API HTTP calls) ---
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.logging)

    // --- Serialization + DateTime ---
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)

    // --- Dependency Injection ---
    implementation(libs.koin.ktor)
    implementation(libs.koin.slf4j)

    // --- Redis (Upstash-compatible via Lettuce) ---
    implementation(libs.lettuce.core)

    // --- Logging ---
    implementation(libs.logback)

    // --- Testing ---
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}
