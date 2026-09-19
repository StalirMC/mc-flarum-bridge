#!/usr/bin/env node
/**
 * Zero-dependency mock of the Flarum side of the MC Bridge machine API.
 *
 * It exists so the wire protocol can be exercised end-to-end without a real
 * Flarum installation, a database or a web server. The implemented contract is
 * the one described in `protocol/README.md` and `docs/API.md`:
 *
 *   GET  /api/mc-bridge/outbox        HMAC   (alias: /announcements)
 *   POST /api/mc-bridge/bind/start    HMAC
 *   GET  /api/mc-bridge/bind/status   HMAC
 *   POST /api/mc-bridge/report        HMAC
 *
 * Authentication mirrors `Stalir\McBridge\Api\Controller\AbstractBridgeController`
 * and `Stalir\McBridge\Service\BridgeCrypto`:
 *
 *   signature = hex(HMAC-SHA256(secret, canonicalString))
 *   canonicalString = {timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}
 *
 * where `path` starts at the bridge prefix and never carries a query string.
 * The timestamp must be within +/-300 seconds and a nonce may be used once
 * (cached for 600 seconds).
 *
 * Storage is a plain in-memory object standing in for the `mc_outbox`,
 * `mc_bindings`, `mc_bind_codes` and `mc_reports` tables.
 *
 * Environment variables:
 *   MOCK_SECRET  shared secret (default: the TEST_SECRET constant below)
 *   MOCK_PORT    TCP port to listen on (default: 8791; 0 = ephemeral)
 *
 * Usage:
 *   node tools/mock-flarum.mjs                 # start the HTTP server
 *   import { createServer } from './mock-flarum.mjs';
 *   const mock = await createServer();         # embed in a test
 */

import { createServer as createHttpServer } from 'node:http';
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

// ---------------------------------------------------------------------------
// Constants
// ---------------------------------------------------------------------------

/** Header names, identical to Signature.java / BridgeCrypto.php. */
export const HEADERS = {
  timestamp: 'X-MC-Timestamp',
  nonce: 'X-MC-Nonce',
  signature: 'X-MC-Signature',
  server: 'X-MC-Server',
};

/** Public URL prefix of the bridge API. */
export const BRIDGE_PREFIX = '/api/mc-bridge';

/**
 * Route prefix every bridge route lives under. The canonical signed path starts
 * here, NOT at BRIDGE_PREFIX: Flarum strips the api frontend prefix (`/api`)
 * before the controller runs, so the forum sees `/mc-bridge/outbox` while
 * the client requests `/api/mc-bridge/outbox`. Signing the raw request path
 * makes the two sides disagree and every request fails with 401.
 */
export const SIGN_MARKER = '/mc-bridge';

/** Allowed clock skew in seconds (BridgeCrypto::MAX_SKEW). */
export const MAX_SKEW = 300;

/** How long a spent nonce is remembered, in seconds. */
export const NONCE_TTL = 600;

/** Default listening port (MOCK_PORT). */
export const DEFAULT_PORT = 8791;

/** Default shared secret (MOCK_SECRET). */
export const TEST_SECRET = 'mock-bridge-secret-0123456789abcdef';

/** Unambiguous binding-code alphabet: no 0, O, 1 or I (McBindCode::generateCode). */
export const CODE_ALPHABET = 'ABCDEFGHJKLMNPQRSTUVWXYZ23456789';

/** Binding code length and lifetime in seconds (McBindCode::TTL_MINUTES). */
export const CODE_LENGTH = 8;
export const CODE_TTL_SECONDS = 600;

/** Report field limits (ReportController). */
export const REPORT_MAX_REASON_LENGTH = 1000;
export const REPORT_MAX_NAME_LENGTH = 64;

const DEFAULT_OUTBOX_LIMIT = 20;
const MAX_OUTBOX_LIMIT = 100;
const UUID_PATTERN = /^[0-9a-f]{8}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{4}-?[0-9a-f]{12}$/;

// ---------------------------------------------------------------------------
// Signing helpers (byte-for-byte equivalent to Signature.java)
// ---------------------------------------------------------------------------

/**
 * Build the canonical string both sides sign.
 *
 * @param {string|number} timestamp
 * @param {string} nonce
 * @param {string} method
 * @param {string} path
 * @param {string|null} body
 * @returns {string}
 */
export function canonicalString(timestamp, nonce, method, path, body) {
  // Exactly five components joined by "\n" — no trailing newline, mirroring
  // Signature.java's `... + path + "\n" + body` construction.
  return [
    String(timestamp),
    String(nonce),
    String(method).toUpperCase(),
    String(path),
    body === null || body === undefined ? '' : String(body),
  ].join('\n');
}

/**
 * Lowercase hex HMAC-SHA256 signature.
 *
 * @returns {string}
 */
export function sign(secret, timestamp, nonce, method, path, body = '') {
  return createHmac('sha256', Buffer.from(String(secret), 'utf8'))
    .update(canonicalString(timestamp, nonce, method, path, body), 'utf8')
    .digest('hex');
}

/**
 * Normalise a request path the way BridgeCrypto::normalizePath() does: drop
 * everything before `/mc-bridge`, so neither the api frontend prefix nor a
 * Flarum sub-directory changes the signature.
 *
 * @param {string} rawPath
 * @returns {string}
 */
export function normalizePath(rawPath) {
  let path = String(rawPath ?? '');
  const query = path.indexOf('?');
  if (query >= 0) path = path.slice(0, query);
  const hash = path.indexOf('#');
  if (hash >= 0) path = path.slice(0, hash);
  path = '/' + path.replace(/^\/+/, '');

  const position = path.indexOf(SIGN_MARKER);
  if (position >= 0) path = path.slice(position);

  return path === '' ? '/' : path;
}

/** Constant-time hex signature comparison. */
function constantTimeEquals(a, b) {
  if (typeof a !== 'string' || typeof b !== 'string') return false;
  if (a.length !== b.length) return false;
  const left = Buffer.from(a, 'utf8');
  const right = Buffer.from(b, 'utf8');
  if (left.length !== right.length) return false;
  return timingSafeEqual(left, right);
}

/** Random 32-hex-character nonce (same shape as Signature.newNonce()). */
export function newNonce() {
  return randomBytes(16).toString('hex');
}

/** Random binding code over the unambiguous alphabet. */
export function generateCode() {
  const bytes = randomBytes(CODE_LENGTH);
  let code = '';
  for (let index = 0; index < CODE_LENGTH; index++) {
    code += CODE_ALPHABET[bytes[index] % CODE_ALPHABET.length];
  }
  return code;
}

// ---------------------------------------------------------------------------
// Small formatting / parsing utilities
// ---------------------------------------------------------------------------

/** ISO-8601 with a +00:00 offset, matching Carbon::toIso8601String(). */
function iso(seconds) {
  const date = new Date(seconds * 1000);
  if (Number.isNaN(date.getTime())) return null;
  const pad = (value, width = 2) => String(Math.abs(value)).padStart(width, '0');
  const offset = -date.getTimezoneOffset();
  const signChar = offset < 0 ? '-' : '+';
  return (
    `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}` +
    `T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}` +
    `${signChar}${pad(Math.trunc(offset / 60))}:${pad(offset % 60)}`
  );
}

/** Current unix time in whole seconds. */
function nowSeconds() {
  return Math.floor(Date.now() / 1000);
}

/** PHP filter_var(..., FILTER_VALIDATE_BOOLEAN) equivalent. */
function toBoolean(value, fallback = false) {
  if (value === undefined || value === null || value === '') return fallback;
  if (typeof value === 'boolean') return value;
  const normalised = String(value).trim().toLowerCase();
  if (['1', 'true', 'yes', 'on'].includes(normalised)) return true;
  if (['0', 'false', 'no', 'off', ''].includes(normalised)) return false;
  return fallback;
}

/** Lowercase, hyphen-insensitive UUID validation (sanitizeUuid()). */
function sanitizeUuid(value) {
  if (typeof value !== 'string') return null;
  const uuid = value.trim().toLowerCase();
  return UUID_PATTERN.test(uuid) ? uuid : null;
}

/** Server key validation (resolveServerKey()). */
function sanitizeServerKey(value) {
  if (typeof value !== 'string') return null;
  const key = value.trim();
  if (key === '' || key.length > 100 || !/^[A-Za-z0-9._-]+$/.test(key)) return null;
  return key;
}

/** Numeric JSON value or null. */
function optionalNumber(value) {
  if (value === undefined || value === null || value === '') return null;
  const number = Number(value);
  return Number.isFinite(number) ? number : null;
}

function clampText(value, maxLength) {
  if (value === undefined || value === null) return null;
  return String(value).slice(0, maxLength);
}

// ---------------------------------------------------------------------------
// In-memory stand-ins for the Flarum tables
// ---------------------------------------------------------------------------

/**
 * Create a fresh in-memory store, optionally seeded with an outbox message.
 *
 * @param {{seedOutbox?: boolean|null}} [options]
 */
export function createStore(options = {}) {
  const store = {
    /** mc_outbox */
    outbox: [],
    /** mc_bindings */
    bindings: new Map(),
    /** mc_bind_codes */
    bindCodes: [],
    /** mc_reports */
    reports: [],

    nextOutboxId: 1,
    nextReportId: 1,

    /** Reset every table; a demo message is seeded unless suppressed. */
    reset(resetOptions = {}) {
      store.outbox.length = 0;
      store.bindings.clear();
      store.bindCodes.length = 0;
      store.reports.length = 0;
      store.nextOutboxId = 1;
      store.nextReportId = 1;

      // null  -> never seed;  true/undefined -> seed;  false -> seed only when
      // the server was created with seedOutbox enabled.
      const shouldSeed =
        resetOptions.seedOutbox === null
          ? false
          : resetOptions.seedOutbox !== undefined
            ? resetOptions.seedOutbox
            : options.seedOutbox === undefined || options.seedOutbox === true;

      if (shouldSeed) store.seedMessage();
    },

    /** Insert one queued message. `serverKey === null` targets every server. */
    seedMessage(message = {}) {
      const record = {
        id: message.id ?? store.nextOutboxId++,
        server_key: message.server_key ?? message.serverKey ?? null,
        type: message.type ?? 'announcement',
        title: message.title ?? 'Mock announcement',
        body: message.body ?? 'Queued by tools/mock-flarum.mjs.',
        url: message.url ?? null,
        payload: message.payload ?? {},
        created_at: message.created_at ?? nowSeconds(),
        delivered_at: message.delivered_at ?? null,
      };
      store.nextOutboxId = Math.max(store.nextOutboxId, record.id + 1);
      store.outbox.push(record);
      return record;
    },

    /** Messages pending for a server (null server_key = to all servers). */
    pendingMessages(serverKey) {
      return store.outbox.filter(
        (message) =>
          message.delivered_at === null &&
          (message.server_key === null || message.server_key === serverKey)
      );
    },

    /** Count of pending messages for a server. */
    pendingCount(serverKey) {
      return store.pendingMessages(serverKey).length;
    },

    /** Bind a UUID to a forum account (mc_bindings). */
    bind(playerUuid, user = {}) {
      const uuid = sanitizeUuid(playerUuid);
      if (uuid === null) throw new TypeError('bind() needs a valid player UUID');
      const binding = {
        user_id: user.user_id ?? 1,
        username: user.username ?? 'MockUser',
        player_uuid: uuid,
        player_name: user.player_name ?? user.username ?? 'MockUser',
        server_key: user.server_key ?? 'survival',
        linked_at: user.linked_at ?? nowSeconds(),
      };
      store.bindings.set(uuid, binding);
      return binding;
    },

    /** Record one player report (mc_reports). */
    addReport(report = {}) {
      const record = {
        id: store.nextReportId++,
        server_key: report.server_key ?? 'survival',
        reporter_uuid: report.reporter_uuid ?? null,
        reporter_name: report.reporter_name ?? null,
        target_name: report.target_name ?? 'Unknown',
        reason: report.reason ?? '',
        status: 'pending',
        created_at: nowSeconds(),
      };
      store.reports.push(record);
      return record;
    },

  };

  store.reset();
  return store;
}

/** Wire shape of a mc_outbox row (McOutboxMessage::toApiPayload). */
function outboxPayload(message) {
  return {
    id: message.id,
    type: message.type,
    title: message.title,
    body: message.body,
    url: message.url,
    payload: message.payload ?? {},
    created_at: iso(message.created_at),
  };
}

// ---------------------------------------------------------------------------
// HTTP layer
// ---------------------------------------------------------------------------

function sendJson(response, status, payload) {
  const body = Buffer.from(JSON.stringify(payload), 'utf8');
  response.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': body.length,
    'Cache-Control': 'no-store',
  });
  response.end(body);
}

function sendError(response, status, message, extra = {}) {
  sendJson(response, status, { error: message, ...extra });
}

/** Read the raw request body; it must stay byte-identical to what was signed. */
function readRawBody(request) {
  return new Promise((resolve, reject) => {
    const chunks = [];
    request.on('data', (chunk) => chunks.push(chunk));
    request.on('end', () => resolve(Buffer.concat(chunks).toString('utf8')));
    request.on('error', reject);
  });
}

function parseJsonBody(raw) {
  if (raw === '' || raw === undefined || raw === null) return {};
  try {
    const parsed = JSON.parse(raw);
    return parsed !== null && typeof parsed === 'object' ? parsed : {};
  } catch {
    return {};
  }
}

/**
 * Create (but do not yet start) a mock forum.
 *
 * @param {{
 *   secret?: string,
 *   port?: number,
 *   seedOutbox?: boolean|null,
 *   quiet?: boolean,
 * }} [options]
 * @returns {{ server: import('node:http').Server, store: object, secret: string,
 *             port: number, url: string, listen: Function, close: Function,
 *             createMessage: Function, bind: Function, reset: Function }}
 */
export function createServer(options = {}) {
  const secret = options.secret ?? process.env.MOCK_SECRET ?? TEST_SECRET;
  const requestedPort = options.port ?? Number(process.env.MOCK_PORT ?? DEFAULT_PORT);
  const port = Number.isFinite(requestedPort) ? requestedPort : DEFAULT_PORT;
  const quiet = options.quiet ?? false;

  const store = createStore(
    options.seedOutbox === undefined ? {} : { seedOutbox: options.seedOutbox }
  );
  /** Spent nonces: nonce -> unix seconds when it was first seen. */
  const usedNonces = new Map();

  function pruneNonces(now) {
    for (const [nonce, seenAt] of usedNonces) {
      if (now - seenAt > NONCE_TTL) usedNonces.delete(nonce);
    }
  }

  /**
   * Verify the four X-MC-* headers.
   *
   * @returns {{ status: number, error: string, extra?: object }|null} null when authentic.
   */
  function authenticate(request, rawBody, signedPath) {
    if (secret === '') {
      return {
        status: 503,
        error: 'The MC Bridge secret is not configured on this forum.',
      };
    }

    const timestamp = request.headers[HEADERS.timestamp.toLowerCase()] ?? '';
    const nonce = request.headers[HEADERS.nonce.toLowerCase()] ?? '';
    const signature = request.headers[HEADERS.signature.toLowerCase()] ?? '';

    if (timestamp === '' || nonce === '' || signature === '') {
      return { status: 401, error: 'Missing bridge authentication headers.' };
    }

    if (!/^\d+$/.test(String(timestamp))) {
      return { status: 401, error: 'Malformed timestamp header.' };
    }

    const now = nowSeconds();
    const skew = Math.abs(now - Number(timestamp));
    if (skew > MAX_SKEW) {
      return {
        status: 401,
        error: 'Request timestamp is outside the allowed window.',
        extra: { skew_seconds: skew },
      };
    }

    if (nonce.length < 8 || nonce.length > 128) {
      return { status: 401, error: 'Malformed nonce header.' };
    }

    const expected = sign(secret, timestamp, nonce, request.method, signedPath, rawBody);
    if (!constantTimeEquals(expected, signature)) {
      return { status: 401, error: 'Signature verification failed.' };
    }

    pruneNonces(now);
    if (usedNonces.has(nonce)) {
      return { status: 401, error: 'Duplicate nonce detected (possible replay).' };
    }
    usedNonces.set(nonce, now);

    return null;
  }

  // -------------------------------------------------------------------------
  // Endpoint handlers
  // -------------------------------------------------------------------------

  function handleOutbox(request, response, payload, query, rawBody) {
    const serverKey =
      sanitizeServerKey(query.get('server_key')) ??
      sanitizeServerKey(request.headers[HEADERS.server.toLowerCase()]);

    if (serverKey === null) {
      return sendError(response, 422, 'A valid server_key is required.');
    }

    const requestedLimit = Math.trunc(optionalNumber(query.get('limit')) ?? DEFAULT_OUTBOX_LIMIT);
    const limit = Math.max(1, Math.min(MAX_OUTBOX_LIMIT, requestedLimit));
    const peek = toBoolean(query.get('peek'), false);

    const messages = store
      .pendingMessages(serverKey)
      .sort((a, b) => a.id - b.id)
      .slice(0, limit);

    if (!peek && messages.length > 0) {
      const deliveredAt = nowSeconds();
      for (const message of messages) message.delivered_at = deliveredAt;
    }

    return sendJson(response, 200, {
      ok: true,
      server_key: serverKey,
      peek,
      messages: messages.map(outboxPayload),
    });
  }

  function handleBindStart(request, response, body) {
    const serverKey =
      sanitizeServerKey(body.server_key) ??
      sanitizeServerKey(request.headers[HEADERS.server.toLowerCase()]);

    if (serverKey === null) {
      return sendError(response, 422, 'A valid server_key is required.');
    }

    const uuid = sanitizeUuid(body.player_uuid ?? body.uuid);
    if (uuid === null) {
      return sendError(response, 422, 'A valid player_uuid is required.');
    }

    const username = clampText(body.player_name ?? body.username, 64);

    const binding = store.bindings.get(uuid);
    if (binding) {
      return sendJson(response, 200, {
        ok: true,
        already_bound: true,
        binding: { ...binding, linked_at: iso(binding.linked_at) },
      });
    }

    // A repeat call invalidates the previous unused code for this player.
    store.bindCodes = store.bindCodes.filter(
      (record) => record.player_uuid !== uuid || record.used_at !== null
    );

    let code = generateCode();
    while (store.bindCodes.some((record) => record.code === code)) code = generateCode();

    const expiresAt = nowSeconds() + CODE_TTL_SECONDS;
    store.bindCodes.push({
      code,
      player_uuid: uuid,
      player_name: username,
      server_key: serverKey,
      expires_at: expiresAt,
      used_at: null,
    });

    return sendJson(response, 201, {
      ok: true,
      already_bound: false,
      code,
      expires_at: iso(expiresAt),
      expires_in_seconds: CODE_TTL_SECONDS,
      link_url: '/settings',
    });
  }

  function handleBindStatus(request, response, payload, query, rawBody) {
    const serverKey =
      sanitizeServerKey(query.get('server_key')) ??
      sanitizeServerKey(request.headers[HEADERS.server.toLowerCase()]);

    if (serverKey === null) {
      return sendError(response, 422, 'A valid server_key is required.');
    }

    const uuid = sanitizeUuid(query.get('uuid') ?? query.get('player_uuid'));
    if (uuid === null) {
      return sendError(response, 422, 'A valid uuid query parameter is required.');
    }

    const binding = store.bindings.get(uuid);
    if (binding) {
      return sendJson(response, 200, {
        ok: true,
        bound: true,
        binding: { ...binding, linked_at: iso(binding.linked_at) },
      });
    }

    const pending = [...store.bindCodes]
      .filter((record) => record.player_uuid === uuid && record.used_at === null)
      .sort((a, b) => b.expires_at - a.expires_at)[0];

    const usable = pending !== undefined && pending.expires_at > nowSeconds();

    return sendJson(response, 200, {
      ok: true,
      bound: false,
      pending_code: usable ? pending.code : null,
      expires_at: usable ? iso(pending.expires_at) : null,
    });
  }

  function handleReport(request, response, body) {
    const serverKey =
      sanitizeServerKey(body.server_key) ??
      sanitizeServerKey(request.headers[HEADERS.server.toLowerCase()]);

    if (serverKey === null) {
      return sendError(response, 422, 'A valid server_key is required.');
    }

    const reporterUuid = sanitizeUuid(body.reporter_uuid ?? body.uuid);
    if (reporterUuid === null) {
      return sendError(response, 422, 'A valid reporter_uuid is required.');
    }

    const targetName = clampText(body.target_name, REPORT_MAX_NAME_LENGTH)?.trim() ?? '';
    if (targetName === '') {
      return sendError(response, 422, 'target_name is required.');
    }

    const reason = clampText(body.reason, REPORT_MAX_REASON_LENGTH)?.trim() ?? '';
    if (reason === '') {
      return sendError(response, 422, 'reason is required.');
    }

    const report = store.addReport({
      server_key: serverKey,
      reporter_uuid: reporterUuid,
      reporter_name: clampText(body.reporter_name, REPORT_MAX_NAME_LENGTH),
      target_name: targetName,
      reason,
    });

    return sendJson(response, 201, { ok: true, report_id: report.id });
  }

  // -------------------------------------------------------------------------
  // Routing
  // -------------------------------------------------------------------------

  /**
   * Route table: bridge-relative path -> allowed methods, whether HMAC
   * authentication applies, and the handler. Handlers receive
   * `(request, response, payload, query, rawBody)`.
   *
   * @type {Map<string, {methods: string[], auth: boolean, handler: Function}>}
   */
  const routes = new Map([
    ['/outbox', { methods: ['GET'], auth: true, handler: handleOutbox }],
    ['/announcements', { methods: ['GET'], auth: true, handler: handleOutbox }],
    ['/bind/start', { methods: ['POST'], auth: true, handler: handleBindStart }],
    ['/bind/status', { methods: ['GET'], auth: true, handler: handleBindStatus }],
    ['/report', { methods: ['POST'], auth: true, handler: handleReport }],
  ]);

  const server = createHttpServer(async (request, response) => {
    let rawBody = '';
    try {
      rawBody = await readRawBody(request);
    } catch {
      return sendError(response, 400, 'Unable to read the request body.');
    }

    const url = new URL(request.url ?? '/', 'http://localhost');
    const query = new URLSearchParams(url.searchParams);
    // The signed path is the canonical one starting at SIGN_MARKER; routing keys
    // are relative to the public bridge prefix, which may sit after a Flarum
    // sub-directory (e.g. /forum/api/mc-bridge/...).
    const signedPath = normalizePath(url.pathname);
    const prefixPosition = url.pathname.indexOf(BRIDGE_PREFIX);
    const routePath = prefixPosition >= 0
      ? url.pathname.slice(prefixPosition + BRIDGE_PREFIX.length) || '/'
      : url.pathname;
    const route = routes.get(routePath);

    if (route === undefined) {
      return sendError(response, 404, 'Unknown bridge endpoint.');
    }

    if (!route.methods.includes(request.method ?? 'GET')) {
      response.setHeader('Allow', route.methods.join(', '));
      return sendError(response, 405, 'Method not allowed for this bridge endpoint.');
    }

    try {
      if (route.auth) {
        const failure = authenticate(request, rawBody, signedPath);
        if (failure !== null) {
          return sendError(response, failure.status, failure.error, failure.extra ?? {});
        }
      }

      const body = request.method === 'GET' ? {} : parseJsonBody(rawBody);
      return route.handler(request, response, body, query, rawBody);
    } catch (exception) {
      return sendError(response, 500, `Mock forum error: ${exception?.message ?? exception}`);
    }
  });

  let listeningPort = port;

  function listen(portOverride) {
    const target = portOverride ?? port;
    return new Promise((resolve, reject) => {
      server.once('error', reject);
      server.listen(target, '127.0.0.1', () => {
        server.removeListener('error', reject);
        const address = server.address();
        listeningPort = typeof address === 'object' && address !== null ? address.port : target;
        if (!quiet) {
          console.log(`[mock-flarum] listening on http://127.0.0.1:${listeningPort}`);
          console.log(`[mock-flarum] MOCK_SECRET = ${secret}`);
          console.log('[mock-flarum] machine endpoints require X-MC-Timestamp / X-MC-Nonce / X-MC-Signature');
        }
        resolve(listeningPort);
      });
    });
  }

  function close() {
    return new Promise((resolve) => {
      if (!server.listening) return resolve();
      server.close(() => resolve());
      if (typeof server.closeAllConnections === 'function') server.closeAllConnections();
    });
  }

  return {
    server,
    store,
    secret,
    get port() {
      return listeningPort;
    },
    get url() {
      return `http://127.0.0.1:${listeningPort}`;
    },
    listen,
    close,
    createMessage: (message) => store.seedMessage(message),
    bind: (uuid, user) => store.bind(uuid, user),
    reset: () => {
      usedNonces.clear();
      store.reset();
    },
  };
}

// ---------------------------------------------------------------------------
// CLI entry point
// ---------------------------------------------------------------------------

const isMain =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(process.argv[1]).href;

if (isMain) {
  const portArgument = process.argv[2] !== undefined ? Number(process.argv[2]) : undefined;

  if (portArgument !== undefined && !Number.isFinite(portArgument)) {
    console.error(`[mock-flarum] invalid port: ${process.argv[2]}`);
    process.exit(1);
  }

  const mock = createServer({ port: portArgument });

  mock
    .listen()
    .then((port) => {
      console.log(`[mock-flarum] ready — machine endpoints live under ${BRIDGE_PREFIX} (HMAC signed)`);
      process.on('SIGINT', () => {
        mock.close().then(() => process.exit(0));
      });
      process.on('SIGTERM', () => {
        mock.close().then(() => process.exit(0));
      });
      void port;
    })
    .catch((exception) => {
      console.error(`[mock-flarum] failed to start: ${exception.message}`);
      process.exit(1);
    });
}

export default createServer;
