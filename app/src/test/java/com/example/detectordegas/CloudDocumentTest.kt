package com.example.detectordegas

import org.junit.Assert.*
import org.junit.Test

/** Formato de los documentos de Firestore: debe coincidir con firebase/firestore.rules. */
class CloudDocumentTest {
    @Test fun readingRoundTripsThroughCloudDocument() {
        val r = Reading(620, "alerta", true, 1_700_000_000_000, "ESP32")
        val doc = cloudDocument(r, "Pixel 8 · 3f2a")
        assertEquals(setOf("value", "state", "connected", "source", "time", "device"), doc.keys)
        assertEquals(620L, doc["value"])
        val back = readingFromCloud(doc)
        assertEquals(620, back.value)
        assertEquals("alerta", back.state)
        assertEquals(r.time, back.time)
        assertEquals("Nube · ESP32 · Pixel 8 · 3f2a", back.source)
    }
    @Test fun missingDataStaysMissing() {
        val back = readingFromCloud(cloudDocument(Reading(null, "sin_datos", false, 1L, "ESP32"), "A"))
        assertNull(back.value)
        assertFalse(back.connected)
    }
    @Test fun limitsTextFieldsLikeTheSecurityRules() {
        val doc = cloudDocument(Reading(100, "sin_alerta", true, 1L, "x".repeat(80)), "y".repeat(300))
        assertEquals(40, (doc["source"] as String).length)
        assertEquals(100, (doc["device"] as String).length)
    }
    @Test fun rejectsInvalidCloudDocuments() {
        val valid = cloudDocument(Reading(100, "sin_alerta", true, 1L, "ESP32"), "A")
        listOf(
            valid + ("value" to 2000L),
            valid + ("state" to "desconocido"),
            valid + ("connected" to "true"),
            valid - "time",
            valid + ("value" to null),
        ).forEach { bad -> assertThrows(Exception::class.java) { readingFromCloud(bad) } }
    }
}
