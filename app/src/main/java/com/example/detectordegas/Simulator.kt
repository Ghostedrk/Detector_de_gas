package com.example.detectordegas

const val SIMULATED_SOURCE = "SIMULACIÓN"

/**
 * ESP32 simulado: reproduce la lógica del programa Arduino (espera inicial de 5 s,
 * umbrales 500/450 con histéresis y prueba manual de 3 s) para demostrar la app sin circuito.
 */
class SimulatedDetector(private val clock: () -> Long = System::currentTimeMillis) {
    private val curve = intArrayOf(140, 150, 165, 180, 260, 380, 520, 610, 580, 470, 430, 300, 200, 160)
    private var start = clock()
    private var alert = false
    private var testUntil = 0L

    @Synchronized fun reset() { start = clock(); alert = false; testUntil = 0L }

    /** Equivale a enviar "T" al Arduino. */
    @Synchronized fun triggerTest() { testUntil = clock() + TEST_MS }

    @Synchronized fun read(): Reading {
        val now = clock()
        val elapsed = (now - start).coerceAtLeast(0L)
        val value = curve[((elapsed / STEP_MS) % curve.size).toInt()]
        val warming = elapsed < WARMUP_MS
        alert = when {
            warming -> false
            value >= ALERT_THRESHOLD -> true
            value <= RETURN_THRESHOLD -> false
            else -> alert
        }
        val state = when {
            alert -> "alerta"
            now < testUntil -> "prueba"
            warming -> "calentando"
            else -> "sin_alerta"
        }
        return Reading(value, state, true, now, SIMULATED_SOURCE)
    }

    companion object {
        const val STEP_MS = 2000L
        const val WARMUP_MS = 5000L
        const val TEST_MS = 3000L
        const val ALERT_THRESHOLD = 500
        const val RETURN_THRESHOLD = 450
    }
}
