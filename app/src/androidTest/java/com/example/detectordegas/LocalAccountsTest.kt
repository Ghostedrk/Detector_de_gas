package com.example.detectordegas

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Se ejecuta en un emulador o teléfono: SQLite real, base en memoria. */
@RunWith(AndroidJUnit4::class)
class LocalAccountsTest {
    private fun db() = History(InstrumentationRegistry.getInstrumentation().targetContext, null)
    private val algorithm = "PBKDF2WithHmacSHA256"

    @Test fun firstAccountIsAdministratorAndNextAreOperators() = db().use { h ->
        assertEquals(ROLE_ADMIN, h.register("admin_1", "clave-segura-1", algorithm).role)
        assertEquals(ROLE_OPERATOR, h.register("operador_1", "clave-segura-2", algorithm).role)
        assertEquals(ROLE_ADMIN, h.login("admin_1", "clave-segura-1").role)
        assertEquals(ROLE_OPERATOR, h.login("operador_1", "clave-segura-2").role)
    }

    @Test fun rejectsDuplicateUserAndWrongPasswordThenLocksAfterFiveFailures() = db().use { h ->
        h.register("usuario", "clave-segura-1", algorithm)
        assertThrows(IllegalArgumentException::class.java) { h.register("usuario", "otra-clave-9", algorithm) }
        repeat(5) { assertThrows(IllegalStateException::class.java) { h.login("usuario", "incorrecta") } }
        val locked = assertThrows(IllegalArgumentException::class.java) { h.login("usuario", "clave-segura-1") }
        assertTrue(locked.message!!.contains("30 segundos"))
    }

    @Test fun historyIsSeparatedPerAccount() = db().use { h ->
        h.add("a", Reading(620, "alerta", true))
        h.add("b", Reading(120, "sin_alerta", true))
        assertEquals(listOf(620), h.list("a").map { it.value })
        h.clear("a")
        assertTrue(h.list("a").isEmpty())
        assertEquals(1, h.list("b").size)
    }
}
