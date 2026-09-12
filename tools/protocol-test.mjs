#!/usr/bin/env node
/**
 * Wire-protocol conformance test for the MC Bridge.
 *
 * Boots the zero-dependency mock forum from `tools/mock-flarum.mjs`, then drives
 * it with a JavaScript signer that is algorithmically identical to
 * `mc-plugin/src/main/java/cn/stalir/mcbridge/Signature.java` and
 * `flarum-extension/src/Service/BridgeCrypto.php`:
 *
 *   canonicalString = {timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}
 *   signature       = hex(HMAC-SHA256(secret, canonicalString))
 *
 * Checks performed:
 *   1. the canonical string layout itself
 *   2. HMAC-SHA256 against an independent RFC 2104 / RFC 4231 construction
 *   3. path normalisation (query string dropped, sub-directory stripped)
 *   4. a correctly signed request is accepted (2xx)
 *   5. a wrong secret is rejected with 401
 *   6. a tampered body is rejected with 401
 *   7. a stale timestamp (-400s) is rejected with 401
 *   8. a replayed nonce is rejected with 401
 *   9. GET /api/mc-bridge/status is reachable without any signature
 *  10. heartbeat -> outbox: peek=true does not consume, peek=false does
 *  11. bind/start returns an 8-character code from the unambiguous alphabet
 *  12. /events and /bind/status round-trip
 *
 * Every check prints PASS or FAIL; any failure exits with code 1.
 *
 * Usage: node tools/protocol-test.mjs
 */

import { createHash, createHmac } from 'node:crypto';
import {
  createServer,
  TEST_SECRET,
  BRIDGE_PREFIX,
  CODE_ALPHABET,
  CODE_LENGTH,
  MAX_SKEW,
  canonicalString,
  normalizePath,
} from './mock-flarum.mjs';

// ---------------------------------------------------------------------------
// JS signer — the same algorithm as Signature.java
// ---------------------------------------------------------------------------

const HEADER_TIMESTAMP = 'X-MC-Timestamp';
const HEADER_NONCE = 'X-MC-Nonce';
const HEADER_SIGNATURE = 'X-MC-Signature';
const HEADER_SERVER = 'X-MC-Server';

/**
 * Canonical string, byte-for-byte identical to
 * `Signature.canonicalString(...)` / `BridgeCrypto::canonicalString(...)`.
 *
 * @param {string|number} timestamp
 * @param {string} nonce
 * @param {string} method
 * @param {string} path
 * @param {string|null} [body]
 * @returns {string}
 */
function jsCanonicalString(timestamp, nonce, method, path, body = '') {
  return [
    String(timestamp),
    nonce,
    String(method).toUpperCase(),
    path,
    body === null || body === undefined ? '' : String(body),
  ].join('\n');
}

/**
 * Lowercase hex HMAC-SHA256, identical to `Signature.sign(...)`.
 *
 * @returns {string}
 */
function jsSign(secret, timestamp, nonce, method, path, body = '') {
  return createHmac('sha256', Buffer.from(String(secret), 'utf8'))
    .update(jsCanonicalString(timestamp, nonce, method, path, body), 'utf8')
    .digest('hex');
}

/** Random 32-hex-character nonce, same shape as Signature.newNonce(). */
function jsNonce() {
  return createHmac('sha256', String(Math.random())).update(String(Date.now())).digest('hex').slice(0, 32);
}

// ---------------------------------------------------------------------------
// Tiny test harness
// ---------------------------------------------------------------------------

const results = [];
let currentGroup = 'general';

function group(title) {
  currentGroup = title;
  console.log(`\n\u001b[36m${title}\u001b[0m`);
}

/**
 * Run one labelled check.
 *
 * @param {string} label
 * @param {() => (void|boolean|Promise<void|boolean>)} body Throwing fails the check.
 */
async function test(label, body) {
  try {
    const outcome = await body();
    if (outcome === false) throw new Error('assertion returned false');
    results.push({ group: currentGroup, label, ok: true });
    console.log(`  \u001b[32mPASS\u001b[0m ${label}`);
  } catch (exception) {
    results.push({ group: currentGroup, label, ok: false, detail: exception?.message ?? String(exception) });
    console.log(`  \u001b[31mFAIL\u001b[0m ${label}`);
    console.log(`       ${exception?.message ?? exception}`);
  }
}

function assert(condition, message) {
  if (!condition) throw new Error(message);
}

function assertEqual(actual, expected, message) {
  if (actual !== expected) {
    throw new Error(`${message} — expected ${JSON.stringify(expected)}, got ${JSON.stringify(actual)}`);
  }
}

function assertEqualJson(actual, expected, message) {
  const left = JSON.stringify(actual);
  const right = JSON.stringify(expected);
  if (left !== right) throw new Error(`${message} — expected ${right}, got ${left}`);
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

/**
 * Perform a request, optionally signing it.
 *
 * @param {string} baseUrl
 * @param {{ method: string, path: string, body?: object|null, secret?: string|null,
 *           timestamp?: string|number, nonce?: string, signPath?: string,
 *           headers?: Record<string,string>, signBody?: string|null }} options
 *   `secret: null` sends no authentication headers at all. `signBody`/`signPath`
 *   override what is signed without changing what is transmitted (tamper tests).
 */
async function request(baseUrl, options) {
  const {
    method,
    path,
    body = null,
    secret = TEST_SECRET,
    timestamp = Math.floor(Date.now() / 1000),
    nonce = jsNonce(),
    headers = {},
    signPath = path.split('?')[0],
    signBody = body === null ? null : JSON.stringify(body),
  } = options;

  const requestHeaders = { Accept: 'application/json' };
  const payload = body === null ? null : JSON.stringify(body);

  if (secret !== null) {
    requestHeaders[HEADER_TIMESTAMP] = String(timestamp);
    requestHeaders[HEADER_NONCE] = nonce;
    requestHeaders[HEADER_SIGNATURE] = jsSign(secret, timestamp, nonce, method, signPath, signBody ?? '');
  }

  if (payload !== null) requestHeaders['Content-Type'] = 'application/json; charset=utf-8';

  // Explicit headers win, so a test can tamper with one auth header on purpose.
  Object.assign(requestHeaders, headers);

  const response = await fetch(`${baseUrl}${path}`, {
    method,
    headers: requestHeaders,
    body: payload ?? undefined,
  });

  const text = await response.text();
  let json = null;
  try {
    json = text === '' ? null : JSON.parse(text);
  } catch {
    json = null;
  }

  return { status: response.status, headers: response.headers, text, json, nonce, timestamp };
}

/** Assert an HTTP status code. */
function assertStatus(response, expected, message) {
  assertEqual(response.status, expected, `${message} (body: ${response.text.slice(0, 200)})`);
}

// ---------------------------------------------------------------------------
// Section 1-3: pure signing/normalisation checks (no server needed)
// ---------------------------------------------------------------------------

async function checkSigningPrimitives() {
  group('1. Canonical string (Signature.java semantics)');

  await test('canonical string joins five components with \\n and no trailing newline', () => {
    const value = jsCanonicalString('1700000000', 'abcdefgh', 'POST', '/api/mc-bridge/heartbeat', '{"a":1}');
    assertEqual(
      value,
      '1700000000\nabcdefgh\nPOST\n/api/mc-bridge/heartbeat\n{"a":1}',
      'canonical string layout'
    );
    assert(!value.endsWith('\n'), 'canonical string must not end with a newline');
  });

  await test('method is upper-cased (Locale.ROOT) and a missing body becomes the empty string', () => {
    assertEqual(
      jsCanonicalString(1, 'n', 'get', '/api/mc-bridge/outbox', null),
      '1\nn\nGET\n/api/mc-bridge/outbox\n',
      'GET canonical string'
    );
  });

  await test('tools/mock-flarum.mjs computes the same canonical string', () => {
    assertEqual(
      canonicalString('1700000000', 'abcdefgh', 'POST', '/api/mc-bridge/heartbeat', '{"a":1}'),
      jsCanonicalString('1700000000', 'abcdefgh', 'POST', '/api/mc-bridge/heartbeat', '{"a":1}'),
      'mock vs test signer'
    );
    assertEqual(
      canonicalString(1, 'n', 'get', '/api/mc-bridge/outbox', null),
      jsCanonicalString(1, 'n', 'get', '/api/mc-bridge/outbox', null),
      'mock vs test signer (GET)'
    );
  });

  group('2. HMAC-SHA256 correctness');

  await test('signer reproduces the RFC 4231 test-case-1 HMAC vector', () => {
    // key = 20 x 0x0b, data = "Hi There"
    const key = Buffer.alloc(20, 0x0b).toString('latin1');
    const digest = createHmac('sha256', Buffer.from(key, 'latin1')).update('Hi There', 'utf8').digest('hex');
    assertEqual(
      digest,
      'b0344c61d8db38535ca8afceaf0bf12b881dc200c9833da726e9376c2e32cff7',
      'Node HMAC matches the published vector'
    );
  });

  await test('signer matches an independent RFC 2104 ipad/opad construction', () => {
    const key = 'mock-bridge-secret-0123456789abcdef';
    const message = jsCanonicalString('1750000000', 'abc', 'GET', '/api/mc-bridge/outbox', '');
    const blockSize = 64;
    const padded = Buffer.concat([
      Buffer.from(key, 'utf8'),
      Buffer.alloc(blockSize - Buffer.byteLength(key)),
    ]);
    const innerKey = Buffer.alloc(blockSize);
    const outerKey = Buffer.alloc(blockSize);
    for (let index = 0; index < blockSize; index++) {
      innerKey[index] = padded[index] ^ 0x36;
      outerKey[index] = padded[index] ^ 0x5c;
    }

    const inner = createHash('sha256').update(Buffer.concat([innerKey, Buffer.from(message, 'utf8')])).digest();
    const reference = createHash('sha256').update(Buffer.concat([outerKey, inner])).digest('hex');

    assertEqual(jsSign(key, '1750000000', 'abc', 'GET', '/api/mc-bridge/outbox', ''), reference, 'JS signer');
  });

  await test('signature is 64 lowercase hex characters', () => {
    const signature = jsSign(TEST_SECRET, '1700000000', 'abcdefgh', 'POST', '/api/mc-bridge/heartbeat', '{}');
    assert(/^[0-9a-f]{64}$/.test(signature), `unexpected signature ${signature}`);
  });

  group('3. Path normalisation');

  await test('query string is excluded from the signed path', () => {
    assertEqual(
      normalizePath('/api/mc-bridge/outbox?peek=1&limit=20'),
      '/api/mc-bridge/outbox',
      'normalizePath drops the query'
    );
  });

  await test('a Flarum sub-directory is stripped from the signed path', () => {
    assertEqual(
      normalizePath('/forum/api/mc-bridge/heartbeat'),
      '/api/mc-bridge/heartbeat',
      'normalizePath strips the prefix'
    );
    assertEqual(BRIDGE_PREFIX, '/api/mc-bridge', 'bridge prefix constant');
  });
}

// ---------------------------------------------------------------------------
// Section 4-12: live HTTP checks against the mock
// ---------------------------------------------------------------------------

async function checkAgainstMock(mock) {
  const baseUrl = mock.url;

  group('4. Correctly signed requests are accepted');

  await test('POST /api/mc-bridge/heartbeat with a valid signature returns 200', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: {
        server_key: 'survival',
        online: true,
        name: 'Survival',
        version: 'Paper 1.21.1',
        motd: 'A Minecraft Server',
        players_online: 3,
        players_max: 40,
        tps: 19.97,
        mspt: 12.4,
        player_names: ['Alice', 'Bob', 'Carol'],
      },
    });

    assertStatus(response, 200, 'heartbeat should be accepted');
    assert(response.status >= 200 && response.status < 300, 'heartbeat must be 2xx');
    assertEqual(response.json?.ok, true, 'heartbeat ok flag');
    assert(response.json?.server !== undefined, 'heartbeat must return server state');
    assertEqual(response.json.server.server_key, 'survival', 'server_key echo');
    assertEqual(response.json.server.online, true, 'server reported online');
    assertEqual(response.json.server.players_online, 3, 'players_online');
    assertEqual(response.json.server.players_max, 40, 'players_max');
    assertEqualJson(response.json.server.player_names, ['Alice', 'Bob', 'Carol'], 'player_names');
    assert(typeof response.json.pending_messages === 'number', 'pending_messages must be numeric');
  });

  await test('heartbeat reports the number of queued outbox messages', async () => {
    mock.reset();
    mock.createMessage({ type: 'broadcast', title: 'Maintenance', body: 'Restart tonight', server_key: null });

    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { server_key: 'survival', online: true },
    });

    assertStatus(response, 200, 'heartbeat');
    assertEqual(response.json.pending_messages, 1, 'one message queued for every server');
  });

  await test('X-MC-Server is honoured when the body omits server_key', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { online: true },
      headers: { [HEADER_SERVER]: 'creative' },
    });

    assertStatus(response, 200, 'heartbeat with the header fallback');
    assertEqual(response.json.server.server_key, 'creative', 'server_key from X-MC-Server');
  });

  await test('POST /api/mc-bridge/events stores a batch and returns 201', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/events',
      body: {
        server_key: 'survival',
        events: [
          { type: 'join', player_uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5', player_name: 'Alice' },
          { type: 'death', player_uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5', message: 'cause=FALL' },
        ],
      },
    });

    assertStatus(response, 201, 'events are stored with 201');
    assertEqual(response.json.stored, 2, 'stored count');
    assertEqualJson(response.json.rejected, [], 'rejected list');
  });

  await test('GET /api/mc-bridge/outbox accepts a signed, query-bearing path', async () => {
    mock.reset();
    mock.createMessage({ type: 'announcement', title: 'Renamed', body: 'Second season', url: '/d/50' });

    const response = await request(baseUrl, {
      method: 'GET',
      path: '/api/mc-bridge/outbox?server_key=survival&limit=20&peek=true',
      signPath: '/api/mc-bridge/outbox',
    });

    assertStatus(response, 200, 'outbox pull');
    assertEqual(response.json.server_key, 'survival', 'server_key echo');
    assertEqual(response.json.messages.length, 1, 'one message returned');
    assertEqual(response.json.messages[0].title, 'Renamed', 'message title');
  });

  group('5. Wrong secret is rejected');

  await test('a request signed with the wrong secret returns 401 with an error body', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { server_key: 'survival', online: true },
      secret: 'totally-the-wrong-secret-000000000000',
    });

    assertStatus(response, 401, 'wrong secret must be rejected');
    assert(typeof response.json?.error === 'string' && response.json.error !== '', 'error message required');
  });

  await test('a missing signature header returns 401', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { server_key: 'survival', online: true },
      secret: null,
    });

    assertStatus(response, 401, 'unsigned machine request must be rejected');
    assert(typeof response.json?.error === 'string', 'error message required');
  });

  group('6. Tampered body is rejected');

  await test('a body modified after signing returns 401', async () => {
    const signedBody = { server_key: 'survival', online: false };
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { server_key: 'survival', online: true, players_online: 99 },
      signBody: JSON.stringify(signedBody),
    });

    assertStatus(response, 401, 'tampered body must be rejected');
    assert(/signature/i.test(response.json?.error ?? ''), `error should mention the signature: ${response.text.slice(0, 120)}`);
  });

  await test('a tampered signature itself returns 401', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { server_key: 'survival' },
      headers: { [HEADER_SIGNATURE]: 'f'.repeat(64) },
    });

    assertStatus(response, 401, 'bad signature must be rejected');
  });

  group('7-8. Timestamp window and nonce replay');

  await test(`a timestamp ${MAX_SKEW + 100}s in the past returns 401`, async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { server_key: 'survival' },
      timestamp: Math.floor(Date.now() / 1000) - (MAX_SKEW + 100),
    });

    assertStatus(response, 401, 'stale timestamp must be rejected');
  });

  await test('a timestamp in the far future also returns 401', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { server_key: 'survival' },
      timestamp: Math.floor(Date.now() / 1000) + (MAX_SKEW + 100),
    });

    assertStatus(response, 401, 'future timestamp must be rejected');
  });

  await test('replaying the same nonce returns 401 the second time', async () => {
    const nonce = jsNonce();
    const body = { server_key: 'survival', online: true };
    const timestamp = Math.floor(Date.now() / 1000);

    const first = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body,
      nonce,
      timestamp,
    });
    assertStatus(first, 200, 'first use of the nonce succeeds');

    const replay = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body,
      nonce,
      timestamp,
    });
    assertStatus(replay, 401, 'nonce replay must be rejected');
    assert(/nonce/i.test(replay.json?.error ?? ''), `error should mention the nonce: ${replay.text.slice(0, 120)}`);
  });

  group('9. Public status endpoint');

  await test('GET /api/mc-bridge/status works with no signature at all', async () => {
    const response = await request(baseUrl, { method: 'GET', path: '/api/mc-bridge/status', secret: null });

    assertStatus(response, 200, 'status is public');
    assertEqual(response.json?.ok, true, 'ok flag');
    assert(typeof response.json?.totals === 'object', 'totals object');
    assert(Array.isArray(response.json?.servers), 'servers array');
    assert(Array.isArray(response.json?.recent_events), 'recent_events array');
    assert(response.json.totals.servers >= 1, 'at least one known server');
  });

  await test('the public status payload exposes no secrets', async () => {
    const response = await request(baseUrl, { method: 'GET', path: '/api/mc-bridge/status', secret: null });
    assert(!response.text.includes(mock.secret), 'secret must never appear in the response');
    assert(!/secret/i.test(response.text), 'no secret-looking field in the response');
  });

  group('10. Heartbeat then outbox (peek vs consume)');

  await test('peek=true returns the queued message without consuming it', async () => {
    mock.reset();
    mock.createMessage({
      type: 'announcement',
      title: 'Server rename',
      body: 'Second season starts today.',
      url: '/d/50',
      payload: { discussion_id: 50, post_id: 210, author: 'kxkl2024', is_op: true },
    });

    const heartbeat = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/heartbeat',
      body: { server_key: 'survival', online: true, players_online: 1, players_max: 20 },
    });
    assertStatus(heartbeat, 200, 'heartbeat primes the queue');

    const peek = await request(baseUrl, {
      method: 'GET',
      path: '/api/mc-bridge/outbox?server_key=survival&peek=true',
    });
    assertStatus(peek, 200, 'peek pull');
    assertEqual(peek.json.peek, true, 'peek flag echoed');
    assertEqual(peek.json.messages.length, 1, 'one message visible');
    assertEqual(peek.json.messages[0].title, 'Server rename', 'title');
    assertEqual(peek.json.messages[0].type, 'announcement', 'type');
    assertEqual(peek.json.messages[0].url, '/d/50', 'url');
    assertEqual(peek.json.messages[0].payload?.discussion_id, 50, 'payload passthrough');

    // A second peek must still see the unconsumed message.
    const secondPeek = await request(baseUrl, {
      method: 'GET',
      path: '/api/mc-bridge/outbox?server_key=survival&peek=true',
    });
    assertEqual(secondPeek.json.messages.length, 1, 'peek must not consume');
  });

  await test('peek=false consumes the message and the next pull is empty', async () => {
    const consume = await request(baseUrl, {
      method: 'GET',
      path: '/api/mc-bridge/outbox?server_key=survival&peek=false',
    });
    assertStatus(consume, 200, 'consuming pull');
    assertEqual(consume.json.peek, false, 'peek flag false');
    assertEqual(consume.json.messages.length, 1, 'the message is delivered once');

    const after = await request(baseUrl, {
      method: 'GET',
      path: '/api/mc-bridge/outbox?server_key=survival',
    });
    assertStatus(after, 200, 'second pull');
    assertEqual(after.json.messages.length, 0, 'delivered messages are not returned again');
  });

  await test('the /announcements alias returns the same queue', async () => {
    mock.reset();
    mock.createMessage({ type: 'announcement', title: 'Alias check' });

    const response = await request(baseUrl, {
      method: 'GET',
      path: '/api/mc-bridge/announcements?server_key=survival',
    });

    assertStatus(response, 200, 'announcements alias');
    assertEqual(response.json.messages.length, 1, 'alias shares the outbox');
    assertEqual(response.json.messages[0].title, 'Alias check', 'alias payload');
  });

  group('11. Binding codes');

  await test('bind/start returns 201 and an 8-character unambiguous code', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/bind/start',
      body: {
        server_key: 'survival',
        player_uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
        player_name: 'Alice',
      },
    });

    assertStatus(response, 201, 'new binding code');
    assertEqual(response.json.already_bound, false, 'already_bound flag');
    const code = response.json.code;
    assert(typeof code === 'string', 'code must be a string');
    assertEqual(code.length, CODE_LENGTH, 'code length');
    assert(/^[A-Z0-9]+$/.test(code), `code must be uppercase alphanumeric, got ${code}`);
    assert(!/[0OI1]/.test(code), `code must not contain 0/O/1/I, got ${code}`);

    for (const character of code) {
      assert(CODE_ALPHABET.includes(character), `code character ${character} outside the alphabet`);
    }

    assertEqual(response.json.expires_in_seconds, 600, 'expiry window');
    assert(typeof response.json.expires_at === 'string', 'expires_at must be present');
  });

  await test('the binding alphabet is exactly ABCDEFGHJKLMNPQRSTUVWXYZ23456789', () => {
    assertEqual(CODE_ALPHABET, 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789', 'alphabet contents');
    assertEqual(CODE_ALPHABET.length, 32, 'alphabet size');
    for (const character of '0O1I') {
      assert(!CODE_ALPHABET.includes(character), `alphabet must exclude ${character}`);
    }
  });

  await test('bind/status reports the pending code for an unlinked UUID', async () => {
    const uuid = '069a79f4-44e9-4726-a5be-fca90e38aaf5';
    const response = await request(baseUrl, {
      method: 'GET',
      path: `/api/mc-bridge/bind/status?server_key=survival&uuid=${uuid}`,
    });

    assertStatus(response, 200, 'bind status');
    assertEqual(response.json.bound, false, 'not bound yet');
    assert(/^[A-Z0-9]{8}$/.test(response.json.pending_code ?? ''), `pending code shape: ${response.json.pending_code}`);
  });

  await test('bind/status reports the binding once the account is linked', async () => {
    const uuid = '069a79f4-44e9-4726-a5be-fca90e38aaf5';
    mock.bind(uuid, { user_id: 5, username: 'Alice', player_name: 'Alice', server_key: 'survival' });

    const response = await request(baseUrl, {
      method: 'GET',
      path: `/api/mc-bridge/bind/status?server_key=survival&uuid=${uuid}`,
    });

    assertStatus(response, 200, 'bind status');
    assertEqual(response.json.bound, true, 'bound flag');
    assertEqual(response.json.binding?.user_id, 5, 'bound user id');
    assertEqual(response.json.binding?.username, 'Alice', 'bound username');
  });

  await test('bind/start reports already_bound instead of issuing a second code', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/api/mc-bridge/bind/start',
      body: {
        server_key: 'survival',
        player_uuid: '069a79f4-44e9-4726-a5be-fca90e38aaf5',
        player_name: 'Alice',
      },
    });

    assertStatus(response, 200, 'already bound');
    assertEqual(response.json.already_bound, true, 'already_bound flag');
    assert(response.json.binding !== undefined, 'binding payload expected');
  });

  group('12. Sub-directory and concurrency robustness');

  await test('a request to /forum/api/... verifies against the bare bridge path', async () => {
    const response = await request(baseUrl, {
      method: 'POST',
      path: '/forum/api/mc-bridge/heartbeat',
      body: { server_key: 'survival', online: true },
      signPath: '/api/mc-bridge/heartbeat',
    });

    assertStatus(response, 200, 'sub-directory installs must verify');
  });

  await test('25 concurrent signed requests all succeed with distinct nonces', async () => {
    const responses = await Promise.all(
      Array.from({ length: 25 }, (unused, index) =>
        request(baseUrl, {
          method: 'POST',
          path: '/api/mc-bridge/heartbeat',
          body: { server_key: `node-${index}`, online: true, players_online: index },
        })
      )
    );

    const statuses = responses.map((response) => response.status);
    assert(
      statuses.every((status) => status === 200),
      `expected every concurrent request to return 200, got ${JSON.stringify(statuses)}`
    );
    const keys = responses.map((response) => response.json?.server?.server_key);
    assertEqualJson(
      keys,
      Array.from({ length: 25 }, (unused, index) => `node-${index}`),
      'each concurrent request kept its own body'
    );
  });
}

// ---------------------------------------------------------------------------
// Entry point
// ---------------------------------------------------------------------------

async function main() {
  console.log('MC Bridge protocol conformance test');
  console.log(`  signer     : JS (Signature.java equivalent, HMAC-SHA256)`);
  console.log(`  canonical  : {timestamp}\\n{nonce}\\n{METHOD}\\n{path}\\n{body}`);

  await checkSigningPrimitives();

  const mock = createServer({ port: 0, quiet: true, seedOutbox: false });

  try {
    await mock.listen();
    console.log(`  mock forum : ${mock.url}`);
    console.log(`  secret     : ${mock.secret}`);
    await checkAgainstMock(mock);
  } finally {
    await mock.close();
  }

  const failed = results.filter((result) => !result.ok);
  const passed = results.length - failed.length;

  console.log(`\n${'─'.repeat(64)}`);
  console.log(`checks: ${results.length}   passed: ${passed}   failed: ${failed.length}`);

  if (failed.length > 0) {
    console.log('\n\u001b[31mFAILED\u001b[0m');
    for (const result of failed) console.log(`  • [${result.group}] ${result.label}\n      ${result.detail}`);
    process.exitCode = 1;
    return;
  }

  console.log('\n\u001b[32mALL PROTOCOL CHECKS PASSED\u001b[0m');
}

main().catch((exception) => {
  console.error('\n\u001b[31mprotocol-test crashed\u001b[0m');
  console.error(exception);
  process.exitCode = 1;
});
