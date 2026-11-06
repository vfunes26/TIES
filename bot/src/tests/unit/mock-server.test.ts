import { describe, it } from 'node:test'
import assert from 'node:assert'
import { shouldServeMockPage } from '../../mock-server'

describe('mock server route matching', () => {
    it('accepts HEAD requests for the cita page readiness check', () => {
        assert.strictEqual(shouldServeMockPage('HEAD', '/icpco/citar', {}), true)
        assert.strictEqual(shouldServeMockPage('HEAD', '/icpco/citar', { p: '3' }), true)
    })

    it('serves the live province route and Valencia cita route', () => {
        assert.strictEqual(shouldServeMockPage('GET', '/icpplus/index.html'), true)
        assert.strictEqual(shouldServeMockPage('GET', '/icpplus/citar', { p: '46' }), true)
    })
})
