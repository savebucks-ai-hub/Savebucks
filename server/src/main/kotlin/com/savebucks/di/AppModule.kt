package com.savebucks.di

import com.savebucks.config.AppConfig
import com.savebucks.lib.ai.AiOrchestrator
import com.savebucks.lib.ai.AiProviderRouter
import com.savebucks.lib.redis.RedisCache
import com.savebucks.lib.supabase.SupabaseAdmin
import org.koin.dsl.module

/**
 * Koin DI module — wires every application-level singleton.
 *
 * All singletons are created eagerly here rather than lazily on first request
 * so that startup failures (bad config, unreachable Redis) surface immediately
 * rather than on the first user request.
 */
fun appModule(config: AppConfig) = module {
    // Configuration — injectable throughout the app
    single { config }

    // Supabase admin client — single shared HttpClient with connection pooling
    single { SupabaseAdmin(config.supabase) }

    // Redis cache — degrades to in-memory LRU when REDIS_URL is absent
    single { RedisCache(config.redis) }

    // AI provider router — Groq (free) first, OpenAI fallback, usage tracked in Redis
    single { AiProviderRouter(config.groq, config.openai, get()) }

    // AI orchestrator — intent classification + tool execution + response generation
    single { AiOrchestrator(get(), get(), get()) }
}
