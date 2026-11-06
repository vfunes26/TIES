package es.citaasistente.app

import es.citaasistente.app.data.Nif
import org.junit.Assert.*
import org.junit.Test

class NifTest {
    @Test fun nieValido() { assertTrue(Nif.isValidNie("X1234567L")); assertTrue(Nif.isValidNie("x-1234567-l")) }
    @Test fun nieConLetraMal() { assertFalse(Nif.isValidNie("X1234567A")) }
    @Test fun nieFormatoMal() { assertFalse(Nif.isValidNie("1234567L")) }
    @Test fun dniValido() { assertTrue(Nif.isValidDni("12345678Z")) }
    @Test fun dniLetraMal() { assertFalse(Nif.isValidDni("12345678A")) }
    @Test fun pasaporte() { assertTrue(Nif.isValidPassport("AB123456")); assertFalse(Nif.isValidPassport("A1")) }
    @Test fun tipoSegunFormato() {
        assertEquals("NIE", Nif.guessType("Z0000000T")); assertEquals("DNI", Nif.guessType("12345678Z")); assertEquals("PASAPORTE", Nif.guessType("AB123456"))
    }
}
