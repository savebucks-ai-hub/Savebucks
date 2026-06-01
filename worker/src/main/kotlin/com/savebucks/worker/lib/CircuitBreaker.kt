package com.savebucks.worker.lib

import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

private val log = LoggerFactory.getLogger(CircuitBreaker::class.java)

enum class CircuitState { CLOSED, OPEN, HALF_OPEN }

data class CircuitStatus(val state: CircuitState, val failures: Int, val successCount: Int)

class CircuitOpenException(message: String) : Exception(message)

class CircuitBreaker(
    private val failureThreshold: Int = IngestionConfig.CircuitBreaker.FAILURE_THRESHOLD,
    private val resetTimeoutMs: Long = IngestionConfig.CircuitBreaker.RESET_TIMEOUT_MS,
    private val successThreshold: Int = IngestionConfig.CircuitBreaker.SUCCESS_THRESHOLD,
    private val monitoringWindowMs: Long = IngestionConfig.CircuitBreaker.MONITORING_WINDOW_MS
) {
    private inner class Circuit {
        var state: CircuitState = CircuitState.CLOSED
        val failureTimestamps: ArrayDeque<Long> = ArrayDeque()
        var lastOpenTime: Long = 0L
        var successCount: Int = 0
    }

    private val circuits = ConcurrentHashMap<String, Circuit>()
    private fun getOrCreate(source: String) = circuits.getOrPut(source) { Circuit() }

    suspend fun <T> withCircuitBreaker(source: String, block: suspend () -> T): T {
        val circuit = getOrCreate(source)
        val now = System.currentTimeMillis()

        if (circuit.state == CircuitState.OPEN) {
            if (now - circuit.lastOpenTime >= resetTimeoutMs) {
                circuit.state = CircuitState.HALF_OPEN
                circuit.successCount = 0
                log.info("Circuit [$source] HALF_OPEN — testing recovery")
            } else {
                throw CircuitOpenException("Circuit for '$source' is OPEN — request rejected")
            }
        }

        return try {
            val result = block()
            onSuccess(source, circuit)
            result
        } catch (e: CircuitOpenException) {
            throw e
        } catch (e: Exception) {
            onFailure(source, circuit, now)
            throw e
        }
    }

    private fun onSuccess(source: String, circuit: Circuit) {
        when (circuit.state) {
            CircuitState.HALF_OPEN -> {
                circuit.successCount++
                if (circuit.successCount >= successThreshold) {
                    circuit.state = CircuitState.CLOSED
                    circuit.failureTimestamps.clear()
                    log.info("Circuit [$source] CLOSED — recovered after ${circuit.successCount} successes")
                }
            }
            CircuitState.CLOSED -> { /* nothing to do */ }
            CircuitState.OPEN -> { /* shouldn't reach here */ }
        }
    }

    private fun onFailure(source: String, circuit: Circuit, now: Long) {
        val windowStart = now - monitoringWindowMs
        while (circuit.failureTimestamps.isNotEmpty() && circuit.failureTimestamps.first() < windowStart) {
            circuit.failureTimestamps.removeFirst()
        }
        circuit.failureTimestamps.addLast(now)

        if (circuit.state == CircuitState.HALF_OPEN || circuit.failureTimestamps.size >= failureThreshold) {
            circuit.state = CircuitState.OPEN
            circuit.lastOpenTime = now
            log.warn("Circuit [$source] OPEN — ${circuit.failureTimestamps.size} failures in window")
        }
    }

    fun isOpen(source: String): Boolean = circuits[source]?.state == CircuitState.OPEN

    fun getStatus(source: String): CircuitStatus {
        val c = circuits[source] ?: return CircuitStatus(CircuitState.CLOSED, 0, 0)
        return CircuitStatus(c.state, c.failureTimestamps.size, c.successCount)
    }

    fun reset(source: String) {
        circuits[source]?.apply {
            state = CircuitState.CLOSED
            failureTimestamps.clear()
            successCount = 0
        }
        log.info("Circuit [$source] manually reset to CLOSED")
    }
}
