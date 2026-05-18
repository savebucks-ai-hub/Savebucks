# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Savebucks is a Kotlin Multiplatform project — a deals/coupons platform with a Ktor backend, React web frontend, and a KMP mobile app. The original Node.js API and worker have been migrated to Kotlin.

## Module Map

| Module | Purpose | Targets |
|---|---|---|
| `core` | KMP shared data models (`@Serializable` data classes) | Android, iOS, JVM |
| `server` | Ktor HTTP API server (replaces Node.js Express) | JVM only |
| `worker` | Background job runner (replaces Node.js worker + BullMQ) | JVM only |
| `app:shared` | Compose Multiplatform UI shared across mobile/desktop | Android, iOS, JVM |
| `app:androidApp` | Android thin shell | Android only |
| `app:desktopApp` | Desktop thin shell | JVM only |

**Dependency rule**: `server` and `worker` both depend on `core` for models. `app:shared` also depends on `core`. Never make `server` depend on `app:shared` or vice versa.

## Build & Run

```bash
# API server (port 4000, requires .env set up)
./gradlew :server:run

# Background worker
./gradlew :worker:run

# Desktop app
./gradlew :app:desktopApp:run

# Android
./gradlew :app:androidApp:assembleDebug
```

## Tests

```bash
./gradlew :server:test
./gradlew :app:shared:jvmTest
./gradlew :app:shared:testAndroidHostTest

# Run a single test class
./gradlew :server:test --tests "com.savebucks.ApplicationTest"
```

## Environment Setup (Server)

Copy `server/.env.example` to `server/.env` and fill in:
- `SUPABASE_URL`, `SUPABASE_ANON_KEY`, `SUPABASE_SERVICE_ROLE`, `SUPABASE_JWT_SECRET` — from your Supabase project
- `REDIS_URL` — optional; Upstash Redis URL (`rediss://...`). Falls back to in-memory LRU when absent.
- `OPENAI_API_KEY` — optional; AI chat is disabled gracefully when absent
- `VAPID_PUBLIC_KEY`, `VAPID_PRIVATE_KEY` — optional; web push disabled when absent
- `CORS_ALLOWED_ORIGINS` — comma-separated origins for CORS
- `WEB_DIST_PATH` — path to the built React app (`../app/web/dist`)

For the worker: same Supabase vars, plus optional `TELEGRAM_BOT_TOKEN` and `TELEGRAM_ALLOWED_CHANNELS`.

## Architecture Decisions

### Supabase Integration
All Supabase calls go through `SupabaseAdmin` (`server/src/.../lib/supabase/SupabaseAdmin.kt`). It wraps Ktor's `HttpClient` to talk to Supabase's PostgREST REST API directly — no Supabase SDK dependency. Queries use the fluent `SupabaseQueryBuilder` chain: `.from("table").select("*").eq("status","approved").limit(20).execute()`.

### Authentication
Bearer tokens are validated by calling Supabase's `/auth/v1/user` endpoint on every request (not by verifying the JWT locally). Results are NOT cached per-request — if you need to reduce Supabase auth latency, add a short-TTL Redis cache keyed on the token hash in `Authentication.kt`.

### Caching
`RedisCache` (`server/src/.../lib/redis/RedisClient.kt`) uses Lettuce with an in-memory LRU fallback (max 1 000 entries) when Redis is unavailable. Use `cache.getOrSet(key, ttl) { ... }` for the cache-aside pattern. Use `cache.rateLimit(key, max, windowSeconds)` for sliding-window rate limiting.

### AI Layer
`AiOrchestrator` (`server/src/.../lib/ai/AiOrchestrator.kt`) chains: rate-limit → FAQ short-circuit → cache lookup → intent classification → parallel tool execution → LLM call → cache write. System prompts live in `AiPrompts.kt`. Tools (search_deals, get_coupons, get_trending) live in `AiTools.kt`.

### Worker
`Scheduler` in the `worker` module uses `kotlinx.coroutines` for periodic jobs — no external queue. `WebScraper` uses Jsoup (replaces Puppeteer/Cheerio). `TelegramBot` uses Ktor client for long-polling (replaces Telegraf).

## Key Tech Versions

- Kotlin 2.3.21 / Ktor 3.4.3 / Compose Multiplatform 1.11.0
- Koin 4.0.2 (DI) / Lettuce 6.4.2 (Redis) / Jsoup 1.18.3 (HTML parsing)
- kotlinx.serialization 1.7.3 / kotlinx.datetime 0.7.1
- All versions in `gradle/libs.versions.toml`

## Web App (React)

The React + Tailwind CSS + Vite web app lives in the original GitHub repo (`NithinGoud2605/Savebucks` → `apps/web`). Copy `apps/web` into a `web/` directory at the repo root. Build with `npm run build` inside `web/`. The Ktor server automatically serves `web/dist` as static files when `WEB_DIST_PATH` points to it.
