import { readFile } from 'node:fs/promises'
import assert from 'node:assert/strict'
import test from 'node:test'
import vm from 'node:vm'
import ts from 'typescript'

const source = await readFile(new URL('../src/utils/auth-session.ts', import.meta.url), 'utf8')
const compiled = ts.transpileModule(source, { compilerOptions: { module: ts.ModuleKind.CommonJS, target: ts.ScriptTarget.ES2022 } }).outputText

function setup() {
  const values = new Map()
  const events = []
  const redirects = []
  const timers = []
  const exports = {}
  const sessionStorage = { getItem: key => values.get(key) ?? null, setItem: (key, value) => values.set(key, value), removeItem: key => values.delete(key) }
  const location = { pathname: '/drama', search: '?dramaId=2032095628944588801', hash: '#shot', replace: target => redirects.push(target) }
  vm.runInNewContext(compiled, {
    exports, sessionStorage, Event, Date,
    window: { location, dispatchEvent: event => events.push(event.type) },
    setTimeout: (callback, delay) => { timers.push({ callback, delay }); return timers.length }, clearTimeout: () => {}
  })
  return { auth: exports, values, redirects, events, timers, location }
}

test('a valid login is retained with its expiry and malformed or expired sessions are rejected', () => {
  const { auth, values, timers } = setup()
  assert.equal(auth.getLoginSession(), null)
  const session = { token: 'opaque-token', tokenType: 'Bearer', username: 'admin', expiresAt: new Date(Date.now() + 60000).toISOString() }
  auth.saveLoginSession(session)
  assert.equal(auth.getLoginSession().token, session.token)
  assert.ok(timers[0].delay > 0 && timers[0].delay <= 60000)
  values.set('freyja_login_session', '{invalid')
  assert.equal(auth.getLoginSession(), null)
  values.set('freyja_login_session', JSON.stringify({ ...session, expiresAt: '2020-01-01T00:00:00Z' }))
  assert.equal(auth.getLoginSession(), null)
})

test('expiry clears the session, notifies WebSocket listeners and retains the full return path', () => {
  const { auth, redirects, events, timers } = setup()
  auth.saveLoginSession({ token: 'token', username: 'admin', expiresAt: new Date(Date.now() + 60000).toISOString() })
  timers[0].callback()
  assert.equal(auth.getLoginSession(), null)
  assert.deepEqual(events, ['freyja-logged-out'])
  assert.equal(redirects[0], '/login?redirect=' + encodeURIComponent('/drama?dramaId=2032095628944588801#shot'))
})

test('login redirects accept internal paths and reject external URLs or login loops', () => {
  const { auth } = setup()
  for (const value of ['//evil.example', '/\\evil.example', 'https://evil.example', 'javascript:alert(1)', '/login?redirect=/login', null, ['//evil.example']]) {
    assert.equal(auth.safeLoginRedirect(value), '/dashboard')
  }
  assert.equal(auth.safeLoginRedirect('/drama?dramaId=2032095628944588801'), '/drama?dramaId=2032095628944588801')
})

test('an unauthorized login response stays on the login page', () => {
  const { auth, location, redirects } = setup()
  location.pathname = '/login'
  auth.expireLogin()
  assert.equal(redirects.length, 0)
})
