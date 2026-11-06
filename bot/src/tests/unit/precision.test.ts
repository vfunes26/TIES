import { describe, it } from 'node:test'
import assert from 'node:assert'
import { normalize, suggest } from '../../lib/text'
import { matchOffice } from '../../lib/office'
import { matchProvinceOption } from '../../lib/province'
import { matchSelectOption } from '../../lib/select'
import { validateConfig } from '../../lib/validate'
import { findNationality } from '../../lib/catalog'
import type { AppConfig } from '../../config'

const baseCfg = (): AppConfig => ({
    location: '08',
    tramiteLabel: 'POLICÍA-TOMA DE HUELLAS (EXPEDICIÓN DE TARJETA) INICIAL, RENOVACIÓN, DUPLICADO Y LEY 14/2013',
    tramiteValue: '4010',
    offices: ['CNP RAMBLA GUIPUSCOA 74'],
    minCitaDate: '2026-11-05',
    personalData: { nie: 'X1234567L', nombre: 'Ana Prueba', paisNac: 'PERU', telefono: '600123123', email: 'ana@example.com' },
})

describe('normalize', () => {
    it('quita tildes, backticks y espacios dobles', () => {
        assert.strictEqual(normalize('  L`HOSPITALET  de   Llobregat '), 'lhospitalet de llobregat')
        assert.strictEqual(normalize('COMISARÍA'), 'comisaria')
    })
})

describe('matchOffice (precisión)', () => {
    it('ignora tildes y mayúsculas', () => {
        const opts = [{ text: 'CNP COMISARIA DE MARÍTIMO EXTRANJERÍA, ERNESTO ANASTASIO, 60, VALENCIA', value: '7' }]
        assert.strictEqual(matchOffice(opts, ['comisaria de maritimo extranjeria']), '7')
    })
    it('prefiere coincidencia exacta a parcial', () => {
        const opts = [
            { text: 'CNP Alcoy Norte', value: '1' },
            { text: 'CNP Alcoy', value: '2' },
        ]
        assert.strictEqual(matchOffice(opts, ['cnp alcoy']), '2')
    })
    it('respeta el orden de prioridad configurado', () => {
        const opts = [{ text: 'Sede A', value: '1' }, { text: 'Sede B', value: '2' }]
        assert.strictEqual(matchOffice(opts, ['sede b', 'sede a']), '2')
    })
})

describe('matchProvinceOption', () => {
    const opts = [
        { text: 'Seleccionar', value: '' },
        { text: 'Barcelona', value: '/icpplustieb/citar?p=8&locale=es' },
        { text: 'S.Cruz Tenerife', value: '/icpplustieb/citar?p=38&locale=es' },
    ]
    it('acepta nombre', () => assert.strictEqual(matchProvinceOption(opts, 'barcelona'), opts[1].value))
    it('acepta código con cero', () => assert.strictEqual(matchProvinceOption(opts, '08'), opts[1].value))
    it('acepta código largo', () => assert.strictEqual(matchProvinceOption(opts, '38'), opts[2].value))
    it('devuelve null si no existe', () => assert.strictEqual(matchProvinceOption(opts, 'Atlantis'), null))
})

describe('matchSelectOption', () => {
    const opts = [{ text: 'PERU', value: '242' }, { text: 'COLOMBIA', value: '480' }]
    it('por valor', () => assert.strictEqual(matchSelectOption(opts, '480'), '480'))
    it('por texto exacto (sin tildes)', () => assert.strictEqual(matchSelectOption(opts, 'Perú'), '242'))
    it('NO acepta coincidencias parciales', () => {
        const guinea = [{ text: 'GUINEA ECUATORIAL', value: '1' }, { text: 'GUINEA REPUBLICA', value: '2' }]
        assert.strictEqual(matchSelectOption(guinea, 'GUINEA'), null)
    })
})

describe('nacionalidades (lista oficial del portal)', () => {
    it('resuelve por código y por nombre', () => {
        assert.strictEqual(findNationality('242')?.label, 'PERU')
        assert.strictEqual(findNationality('Perú')?.value, '242')
    })
    it('rechaza nombres ambiguos o inexistentes', () => {
        assert.strictEqual(findNationality('GUINEA'), null)
        assert.strictEqual(findNationality('Narnia'), null)
    })
})

describe('validateConfig', () => {
    it('acepta una configuración correcta', () => {
        const r = validateConfig(baseCfg())
        assert.deepStrictEqual(r.errors, [])
    })
    it('detecta provincia inexistente y sugiere', () => {
        const c = baseCfg(); c.location = 'Barcelon'
        const r = validateConfig(c)
        assert.ok(r.errors.some((e) => e.includes('Barcelona')))
    })
    it('detecta trámite inexistente', () => {
        const c = baseCfg(); c.tramiteValue = '9999'
        assert.ok(validateConfig(c).errors.some((e) => e.includes('9999')))
    })
    it('detecta nacionalidad que no existe en el portal', () => {
        const c = baseCfg(); c.personalData.paisNac = 'Peruu'
        assert.ok(validateConfig(c).errors.some((e) => e.includes('Nacionalidad')))
    })
    it('detecta teléfono, correo y fecha inválidos', () => {
        const c = baseCfg(); c.personalData.telefono = '12'; c.personalData.email = 'x'; c.minCitaDate = '05/11/2026'
        assert.strictEqual(validateConfig(c).errors.length, 3)
    })
    it('suggest devuelve el más parecido primero', () => {
        assert.strictEqual(suggest('Valenzia', ['Valencia', 'Madrid', 'Murcia'])[0], 'Valencia')
    })
})
