package com.example.detectordegas

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class SensorProtocolTest {
    @Test fun acceptsOriginalEspPayload() {
        val r = Reading.parse(JSONObject("""{"lectura":620,"estado":"alerta","conexion_arduino":true,"unidad":"ADC"}"""))
        assertEquals(620, r.value)
        assertEquals("alerta", r.state)
        assertTrue(r.connected)
    }
    @Test fun missingDataDoesNotBecomeZeroOrSafe() {
        val r = Reading.parse(JSONObject("""{"lectura":null,"estado":"sin_datos","conexion_arduino":false,"unidad":"ADC"}"""))
        assertNull(r.value)
        assertFalse(r.connected)
        assertEquals("sin_datos", r.state)
    }
    @Test fun acceptsOmissionOfNullField() {
        val r = Reading.parse(JSONObject("""{"estado":"sin_datos","conexion_arduino":false,"unidad":"ADC"}"""))
        assertNull(r.value)
        assertFalse(r.connected)
    }
    @Test fun rejectsOutOfRangeUnknownUnitOrContradictoryState() {
        listOf(
            """{"lectura":1.5,"estado":"sin_alerta","conexion_arduino":true,"unidad":"ADC"}""",
            """{"lectura":"120","estado":"sin_alerta","conexion_arduino":true,"unidad":"ADC"}""",
            """{"lectura":1024,"estado":"alerta","conexion_arduino":true,"unidad":"ADC"}""",
            """{"lectura":120,"estado":"sin_alerta","conexion_arduino":true,"unidad":"ppm"}""",
            """{"lectura":null,"estado":"sin_alerta","conexion_arduino":true,"unidad":"ADC"}""",
            """{"lectura":0,"estado":"sin_alerta","conexion_arduino":false,"unidad":"ADC"}""",
            """{"lectura":100,"estado":"desconocido","conexion_arduino":true,"unidad":"ADC"}"""
        ).forEach { raw -> assertThrows(IllegalArgumentException::class.java) { Reading.parse(JSONObject(raw)) } }
    }
}
