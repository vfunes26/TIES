package es.citaasistente.app

import es.citaasistente.app.data.PasswordHasher
import es.citaasistente.app.data.Role
import es.citaasistente.app.data.Roles
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RolesAndAuthTest {

    @Test
    fun masterSeReconoceSinImportarMayusculasNiEspacios() {
        assertEquals(Role.MASTER, Roles.roleOf("master"))
        assertEquals(Role.MASTER, Roles.roleOf("  MASTER "))
        assertEquals(Role.MASTER, Roles.roleOf("Master"))
    }

    @Test
    fun usuarioUiUxAceptaVariasFormasDeEscribirlo() {
        assertEquals(Role.USER, Roles.roleOf("uiux"))
        assertEquals(Role.USER, Roles.roleOf("UI/UX"))
        assertEquals(Role.USER, Roles.roleOf("ui ux"))
        assertEquals(Role.USER, Roles.roleOf("Ui-Ux"))
    }

    @Test
    fun otrosNombresNoEntran() {
        assertNull(Roles.roleOf("admin"))
        assertNull(Roles.roleOf("masterx"))
        assertNull(Roles.roleOf(""))
        assertNull(Roles.roleOf("   "))
    }

    @Test
    fun etiquetasDeLosPerfiles() {
        assertEquals("Master", Roles.label(Role.MASTER))
        assertEquals("Usuario UI/UX", Roles.label(Role.USER))
    }

    @Test
    fun contrasenaCorrectaSeVerifica() {
        val stored = PasswordHasher.create("mi-clave-123")
        assertTrue(PasswordHasher.verify("mi-clave-123", stored))
    }

    @Test
    fun contrasenaIncorrectaSeRechaza() {
        val stored = PasswordHasher.create("mi-clave-123")
        assertFalse(PasswordHasher.verify("mi-clave-124", stored))
        assertFalse(PasswordHasher.verify("", stored))
    }

    @Test
    fun laContrasenaNoSeGuardaEnClaro() {
        val stored = PasswordHasher.create("mi-clave-123")
        assertFalse(stored.contains("mi-clave-123"))
    }

    @Test
    fun cadaHuellaLlevaSalDistinta() {
        val a = PasswordHasher.create("igual")
        val b = PasswordHasher.create("igual")
        assertNotEquals(a, b)
        assertTrue(PasswordHasher.verify("igual", a))
        assertTrue(PasswordHasher.verify("igual", b))
    }

    @Test
    fun huellaDanadaNoRompeYSeRechaza() {
        assertFalse(PasswordHasher.verify("x", ""))
        assertFalse(PasswordHasher.verify("x", "basura"))
        assertFalse(PasswordHasher.verify("x", "p1:zzzz:yyyy"))
        assertFalse(PasswordHasher.verify("x", "p2:AAAA:BBBB"))
    }
}
