plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    application
}

group = "com.savebucks"
version = "1.0.0"

application {
    mainClass = "com.savebucks.worker.WorkerApplicationKt"
}

dependencies {
    // Core shared module (data models)
    api(projects.core)

    // Ktor client for HTTP calls to Supabase and external sites
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Serialization + DateTime
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.coroutines.core)

    // HTML parsing for deal scraping (replaces Cheerio + Puppeteer)
    implementation(libs.jsoup)

    // Redis for job queue signaling
    implementation(libs.lettuce.core)

    // Logging
    implementation(libs.logback)
}
