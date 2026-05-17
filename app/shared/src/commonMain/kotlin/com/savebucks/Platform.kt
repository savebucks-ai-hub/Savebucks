package com.savebucks

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform