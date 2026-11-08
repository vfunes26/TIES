package es.citaasistente.app

import es.citaasistente.app.data.AccessPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessPolicyTest {

    @Test
    fun laClaveSeNormalizaSinEspacios() {
        assertEquals("abcdefghijklmnop", AccessPolicy.normalizeKey("abcd efgh ijkl mnop"))
        assertEquals("abcdefghijklmnop", AccessPolicy.normalizeKey("  abcdefgh ijklmnop "))
    }

    @Test
    fun claveValidaTiene16CaracteresAlfanumericos() {
        assertTrue(AccessPolicy.isValidKey("abcd efgh ijkl mnop"))
        assertTrue(AccessPolicy.isValidKey("abcdefghijklmnop"))
        assertFalse(AccessPolicy.isValidKey("abcdefghijklmno"))
        assertFalse(AccessPolicy.isValidKey("abcdefghijklmnopq"))
        assertFalse(AccessPolicy.isValidKey("abcd-efgh-ijkl-mnp"))
        assertFalse(AccessPolicy.isValidKey(""))
    }

    @Test
    fun correoValido() {
        assertTrue(AccessPolicy.isValidEmail("persona@gmail.com"))
        assertTrue(AccessPolicy.isValidEmail("  persona.apellido+ties@gmail.com "))
        assertFalse(AccessPolicy.isValidEmail("persona"))
        assertFalse(AccessPolicy.isValidEmail("persona@"))
        assertFalse(AccessPolicy.isValidEmail("@gmail.com"))
        assertFalse(AccessPolicy.isValidEmail("perso na@gmail.com"))
    }

    @Test
    fun sinRestriccionValeCualquierCorreo() {
        assertTrue(AccessPolicy.allows("cualquiera@gmail.com", ""))
        assertTrue(AccessPolicy.allows("otro@gmail.com", "   "))
    }

    @Test
    fun conRestriccionSoloEntraEseCorreoSinImportarMayusculas() {
        assertTrue(AccessPolicy.allows("Ties.Usuario@Gmail.com", "ties.usuario@gmail.com"))
        assertTrue(AccessPolicy.allows("  ties.usuario@gmail.com ", "ties.usuario@gmail.com"))
        assertFalse(AccessPolicy.allows("otro@gmail.com", "ties.usuario@gmail.com"))
    }
}
