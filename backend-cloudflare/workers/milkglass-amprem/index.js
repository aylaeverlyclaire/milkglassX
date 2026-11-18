--93e64d81176e9dc61269b6e9e73145f35b623e4921150b6f4a93b5410ab3
Content-Disposition: form-data; name="worker.js"

/**
 * Alight Motion Premium — entitlement provisioning page.
 * Route: milkglass.my.id/amprem*
 *
 * Single-file Worker: the page is embedded, headers are applied at the edge.
 * Every UI string is traceable to a documented source; see the block below.
 */

const CONTINUE_URI = 'https://milkglass.my.id/amprem';

const PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<title>Provision Premium Entitlement — Alight Motion</title>
<style>
  @property --grad-1{syntax:"<color>";inherits:true;initial-value:#2f6bff}
  @property --grad-2{syntax:"<color>";inherits:true;initial-value:#7c5cff}
  @property --grad-3{syntax:"<color>";inherits:true;initial-value:#14b8a6}

  :root{
    box-sizing:border-box;
    padding-top:env(safe-area-inset-top,0px);
    padding-bottom:env(safe-area-inset-bottom,0px);
    color-scheme:light;
    --color-bg:#edf1f8; --color-fg:#0f1722; --color-fg-muted:#586578;
    --color-accent:#2f6bff; --color-danger:#c81e3a; --color-warning:#9a5b00; --color-success:#0f7a55;
    --color-surface:rgba(255,255,255,.66); --color-field:rgba(255,255,255,.75);
    --color-border:rgba(15,23,34,.1); --color-highlight:rgba(255,255,255,.95);
    --shadow-color:rgba(40,60,100,.25); --ambient-opacity:.55;
    transition:--grad-1 1.2s ease,--grad-2 1.2s ease,--grad-3 1.2s ease;
  }
  @media (prefers-color-scheme:dark){
    :root:not([data-theme="light"]){
      color-scheme:dark;
      --color-bg:#080b12; --color-fg:#eef1f7; --color-fg-muted:#97a3b8;
      --color-danger:#ff7a85; --color-warning:#f0b24f; --color-success:#4fd39a;
      --color-surface:rgba(255,255,255,.06); --color-field:rgba(0,0,0,.28);
      --color-border:rgba(255,255,255,.1); --color-highlight:rgba(255,255,255,.14);
      --shadow-color:rgba(0,0,0,.5); --ambient-opacity:.5;
    }
  }
  :root[data-theme="dark"]{
    color-scheme:dark;
    --color-bg:#080b12; --color-fg:#eef1f7; --color-fg-muted:#97a3b8;
    --color-danger:#ff7a85; --color-warning:#f0b24f; --color-success:#4fd39a;
    --color-surface:rgba(255,255,255,.06); --color-field:rgba(0,0,0,.28);
    --color-border:rgba(255,255,255,.1); --color-highlight:rgba(255,255,255,.14);
    --shadow-color:rgba(0,0,0,.5); --ambient-opacity:.5;
  }
  html[data-accent="success"]{--grad-1:#10b981;--grad-2:#14b8a6;--grad-3:#3b82f6}
  html[data-accent="warning"]{--grad-1:#f59e0b;--grad-2:#fb7185;--grad-3:#7c5cff}
  html[data-accent="danger"]{--grad-1:#ef4444;--grad-2:#f43f5e;--grad-3:#7c5cff}

  *{box-sizing:border-box}
  html{background:var(--color-bg);scroll-padding-top:env(safe-area-inset-top,0px)}
  body{margin:0 auto;max-width:440px;min-height:100vh;padding:56px 18px 60px;color:var(--color-fg);
    font:15px/1.55 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif}
  body::before{
    content:"";position:fixed;inset:-20%;z-index:-1;pointer-events:none;opacity:var(--ambient-opacity);
    background:
      radial-gradient(40% 34% at 18% 18%,var(--grad-1),transparent 70%),
      radial-gradient(36% 30% at 86% 32%,var(--grad-2),transparent 70%),
      radial-gradient(42% 34% at 58% 92%,var(--grad-3),transparent 70%);
    animation:drift 40s ease-in-out infinite alternate;will-change:transform;
  }
  @keyframes drift{to{transform:translate3d(4%,3%,0) scale(1.06)}}
  @keyframes fade-in{from{opacity:0;transform:translateY(8px)}}
  @keyframes spin{to{transform:rotate(360deg)}}
  @keyframes stroke-draw{from{stroke-dashoffset:30}}

  h1{font-size:30px;line-height:1.15;font-weight:750;letter-spacing:-.03em;margin:0 0 8px}
  h2{font-size:22px;font-weight:700;letter-spacing:-.02em;margin:14px 0 6px}
  p.sub{color:var(--color-fg-muted);margin:0 0 26px}
  label{display:block;font-size:13px;font-weight:500;color:var(--color-fg-muted);margin:0 0 6px}
  .hint{font-size:12.5px;color:var(--color-fg-muted);margin:6px 0 0}
  .note{font-size:13px;color:var(--color-fg-muted);margin:10px 0 0}
  .note b{color:var(--color-fg)}

  .card{
    background:var(--color-surface);border:1px solid var(--color-border);border-radius:24px;padding:18px;margin-bottom:12px;
    box-shadow:inset 0 1px 0 var(--color-highlight),0 20px 40px -24px var(--shadow-color);
    animation:fade-in .35s ease-out;
  }
  form{margin:0}
  input{
    width:100%;background:var(--color-field);color:var(--color-fg);border:1px solid var(--color-border);
    border-radius:14px;padding:13px 14px;font:inherit;
  }
  input:focus-visible{outline:0;border-color:var(--color-accent);box-shadow:0 0 0 4px color-mix(in srgb,var(--color-accent) 22%,transparent)}
  [aria-invalid="true"]{border-color:var(--color-danger)!important}

  button{
    width:100%;margin-top:12px;padding:14px;cursor:pointer;color:#fff;font:inherit;font-weight:600;
    border:1px solid rgba(255,255,255,.35);border-radius:14px;
    background:linear-gradient(180deg,#5a97ff,var(--color-accent));
    box-shadow:inset 0 1px 0 rgba(255,255,255,.55),0 10px 22px -12px var(--color-accent);
  }
  button:active{opacity:.85}
  button:focus-visible{outline:2px solid var(--color-fg);outline-offset:2px}
  button:disabled{opacity:.55;cursor:default}
  button.ghost{background:var(--color-surface);color:var(--color-fg);border-color:var(--color-border);box-shadow:inset 0 1px 0 var(--color-highlight)}
  button[aria-busy="true"]::after{
    content:"";display:inline-block;width:12px;height:12px;margin-left:8px;vertical-align:-1px;
    border:2px solid currentColor;border-right-color:transparent;border-radius:50%;animation:spin .7s linear infinite;
  }
  .is-hidden{display:none}
  .text-mono{font-family:ui-monospace,monospace;font-size:12.5px;word-break:break-all}

  .notice{
    display:flex;gap:10px;align-items:flex-start;border-radius:14px;padding:11px 12px;margin:0 0 12px;
    border:1px solid var(--color-border);background:color-mix(in srgb,var(--color-fg) 4%,transparent);
  }
  .notice__dot{width:9px;height:9px;border-radius:50%;flex:0 0 auto;margin-top:6px;background:var(--color-fg-muted)}
  .notice--warning{border-color:color-mix(in srgb,var(--color-warning) 40%,transparent);background:color-mix(in srgb,var(--color-warning) 10%,transparent)}
  .notice--warning .notice__dot{background:var(--color-warning)}
  .notice--success{border-color:color-mix(in srgb,var(--color-success) 40%,transparent);background:color-mix(in srgb,var(--color-success) 10%,transparent)}
  .notice--success .notice__dot{background:var(--color-success)}
  .notice p{margin:0;font-size:13.5px}

  .check-badge{
    width:72px;height:72px;border-radius:50%;display:grid;place-items:center;
    background:radial-gradient(circle at 30% 25%,rgba(255,255,255,.7),color-mix(in srgb,var(--color-success) 55%,transparent) 55%,color-mix(in srgb,var(--color-success) 85%,transparent));
    border:1px solid rgba(255,255,255,.5);
    box-shadow:inset 0 2px 0 rgba(255,255,255,.7),inset 0 -10px 18px -8px rgba(0,0,0,.25),0 14px 30px -12px var(--color-success);
  }
  .check-badge svg{width:34px;height:34px;fill:none;stroke:#fff;stroke-width:3.2;stroke-linecap:round;stroke-linejoin:round;
    stroke-dasharray:30;animation:stroke-draw .5s .15s ease-out backwards}

  .alert{border:1px solid var(--color-border);border-radius:16px;padding:13px 14px;margin-top:12px;font-size:14px;word-break:break-word;animation:fade-in .25s ease-out}
  .alert--success{border-color:color-mix(in srgb,var(--color-success) 45%,transparent);background:color-mix(in srgb,var(--color-success) 12%,transparent)}
  .alert--warning{border-color:color-mix(in srgb,var(--color-warning) 45%,transparent);background:color-mix(in srgb,var(--color-warning) 12%,transparent)}
  .alert--danger{border-color:color-mix(in srgb,var(--color-danger) 45%,transparent);background:color-mix(in srgb,var(--color-danger) 12%,transparent)}

  @media (prefers-reduced-motion:reduce){
    body::before,.card,.alert,.check-badge svg,button[aria-busy="true"]::after{animation:none}
    :root{transition:none}
  }
</style>
</head>
<body>

<h1>Provision Alight Motion Premium</h1>
<p class="sub">Enrolls the account and grants a one-year entitlement. Passwordless: the account is identified by its email identifier.</p>

<section class="card" id="view-email">
  <form id="lookup-form" novalidate>
    <label for="email">Account identifier</label>
    <input id="email" type="email" placeholder="user@example.com" autocomplete="email" inputmode="email"
           autocapitalize="none" autocorrect="off" spellcheck="false" enterkeyhint="go"
           aria-describedby="identifier-hint">
    <p class="hint" id="identifier-hint">Email identifier per RFC 822 addr-spec, 256 characters max. Lowercased before lookup; no mailbox verification is performed.</p>
    <button id="submit-email" type="submit">Look up identifier</button>
  </form>
</section>

<section class="card is-hidden" id="view-confirm">
  <div id="confirm-slot"></div>
</section>

<section class="card is-hidden" id="view-success">
  <div class="check-badge"><svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12.5l4.5 4.5L19 7.5"/></svg></div>
  <h2>Entitlement granted</h2>
  <p class="note" style="margin:0">Premium entitlement valid until <b id="expires-at"></b>.</p>
  <p class="note">Order ID: <span class="text-mono" id="order-id"></span></p>
  <p class="note">The account password is machine-generated and never displayed. Use in-app account recovery to regain access.</p>
  <button class="ghost" id="restart" type="button">Provision another identifier</button>
</section>

<div id="status-region" role="status" aria-live="polite" aria-atomic="true"></div>

<script>
'use strict';

/**
 * Alight Motion Premium entitlement provisioning.
 *
 * Engineering order: question requirements, delete, simplify, accelerate, automate.
 *
 * UI strings and their documented sources:
 * - identifier, enrollment, identity provider (IdP), account recovery —
 *   NIST SP 800-63-4 glossary
 * - provisioning, entitlement — RFC 7644 §1, RFC 7643 "entitlements"
 * - precondition, retry — RFC 9110 §13.1, §15.5.13, §10.2.3
 * - user@example.com — RFC 2606, IANA reserved domains
 * - "lookup an email identifier", RFC 822 addr-spec, 256-char limit —
 *   accounts.createAuthUri reference
 * - TimeoutError vs AbortError — MDN AbortSignal.timeout()
 * - generic user copy, detail to console — OWASP Error Handling Cheat Sheet
 */
(() => {
  /* ── configuration ───────────────────────────────────────────────────────── */

  /** Firebase Web API key (public by design). */
  const API_KEY = 'AIzaSyDtG1AU22ErnQD60AzBAcaknySiz9_CEq0';
  const IDENTITY_BASE = 'https://identitytoolkit.googleapis.com/v1';
  const CREATE_AUTH_URI_ENDPOINT = \`\${IDENTITY_BASE}/accounts:createAuthUri?key=\${API_KEY}\`;
  const SIGN_UP_ENDPOINT = \`\${IDENTITY_BASE}/accounts:signUp?key=\${API_KEY}\`;
  const VERIFY_PURCHASE_ENDPOINT =
    'https://us-central1-alight-creative.cloudfunctions.net/verifyPurchase';

  const PRODUCT_ID = 'am.full.sub.annual.19q4';
  const SKU_TYPE = 'subs';
  const ANDROID_PACKAGE = 'com.alightcreative.motion';
  /** Signing certificate SHA-1 pinned by the verifier. */
  const ANDROID_CERT_SHA1 = 'ECA6BF91B8715A6F810ED0BBFC65B6CD578F52A8';
  /** Firebase Installation ID token required by the verifier. */
  const INSTALLATION_ID_TOKEN =
    'cSDnCyp3T-uwp07z3tL86T:APA91bFkmvvsHw5nnqa1SBFci-99DRsKClLiETdRrVcJjS5yBx1v_FbCb1d8WhBuea_zmwnYBktyTIzcRhN4b6uNOUur9wPc0gKXmJDoZic0LhNq5V2s0xI';

  const REQUEST_TIMEOUT_MS = 15_000;
  const ORDER_ID_PREFIX = 'GPA.3389';
  /** Expiry values older than one year are treated as absent. */
  const STALE_WINDOW_MS = 365 * 86_400_000;

  /**
   * WHATWG HTML "valid e-mail address" grammar, the pattern browsers apply to
   * <input type="email">. See html.spec.whatwg.org/multipage/input.html.
   */
  const EMAIL_PATTERN =
    /^[a-zA-Z0-9.!#$%&'*+/=?^_\`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;

  /**
   * Purchase tokens. Every token is verified concurrently and the furthest
   * expiry wins. Append an entry to rotate credentials.
   * @type {ReadonlyArray<string>}
   */
  const PURCHASE_TOKENS = Object.freeze([
    'mmgaobamlahbbeccfplmbkbb.AO-J1OzqG0or_GJJIx-ms8GrTm-jaglCRfhQSRPUZKpl2YspYS-oN7_94uv8RC5vQbvd_Ios2pPDStZ2n7F0hLE3FiOU7HS3R6Fquulv5xLXFECSv4ctElw',
  ]);

  /** Response fields the verifier may use for the expiry timestamp. */
  const EXPIRY_KEYS = Object.freeze([
    'expiresAt', 'expires', 'expiry', 'expiryTimeMillis',
    'premiumUntil', 'validUntil', 'endDate', 'subscriptionExpiry',
  ]);

  const BASE_HEADERS = Object.freeze({
    'content-type': 'application/json',
    accept: 'application/json',
    'x-android-package': ANDROID_PACKAGE,
    'x-android-cert': ANDROID_CERT_SHA1,
  });
  const CLIENT_HEADERS = Object.freeze({
    ...BASE_HEADERS,
    'user-agent': 'Dalvik/2.1.0 (Linux; U; Android 15)',
  });
  const VERIFIER_HEADERS = Object.freeze({
    ...BASE_HEADERS,
    'content-type': 'application/json; charset=utf-8',
    'user-agent': 'okhttp/3.12.1',
    'firebase-instance-id-token': INSTALLATION_ID_TOKEN,
  });

  /** Upstream error codes mapped to remediation copy. */
  const ERROR_MESSAGES = Object.freeze({
    INVALID_EMAIL: 'Identity provider rejected the identifier as malformed. Check the local part and the domain.',
    EMAIL_EXISTS: 'Identifier was registered concurrently. Look it up again.',
    TOO_MANY_ATTEMPTS_TRY_LATER: 'Rate limited by the identity provider. Back off before retrying.',
    QUOTA_EXCEEDED: 'Identity provider quota exhausted. Retry later.',
    PERMISSION_DENIED: 'Identity provider denied the request. Retry; escalate if it persists.',
    PURCHASE_REJECTED: 'No purchase token was accepted. Retry; if it persists, quote the order ID.',
    HTTP_ERROR: 'Request rejected as an unclassified failure. Retry; if it persists, quote the order ID.',
  });

  const OFFLINE_CLIENT = 'Transport unavailable: the client is offline. Restore connectivity and retry.';
  const OFFLINE_UPSTREAM = 'Identity provider unreachable. Check connectivity and retry.';
  const GENERIC_MESSAGE = 'Unclassified failure. Retry; if it persists, quote the order ID.';
  const UNKNOWN_DATE_LABEL = 'an undetermined date';

  /* ── errors ──────────────────────────────────────────────────────────────── */

  /** Upstream rejection carrying the machine-readable code it returned. */
  class ApiError extends Error {
    /**
     * @param {string} message
     * @param {{ code?: string, status?: number }} [meta]
     */
    constructor(message, { code = 'UNKNOWN', status = 0 } = {}) {
      super(message);
      this.name = 'ApiError';
      this.code = code;
      this.status = status;
    }
  }

  /**
   * @param {unknown} error
   * @returns {boolean} true for timeout and fetch-abort rejections.
   */
  const isAbortFailure = (error) => {
    const name = /** @type {any} */ (error)?.name;
    return name === 'TimeoutError' || name === 'AbortError';
  };

  /**
   * Maps any thrown value onto remediation copy. Unknown codes keep their
   * status and code so the report is quotable (OWASP Error Handling).
   * @param {unknown} error
   * @returns {string}
   */
  const describeError = (error) => {
    if (error instanceof ApiError) {
      if (ERROR_MESSAGES[error.code]) return ERROR_MESSAGES[error.code];
      console.debug('[provision]', error);
      const status = error.status || 'n/a';
      return \`Request rejected (HTTP \${status}, \${error.code}). Retry; if it persists, quote the order ID.\`;
    }
    if (isAbortFailure(error)) {
      return \`Request aborted after \${REQUEST_TIMEOUT_MS / 1000} s. Check connectivity and retry.\`;
    }
    if (error instanceof TypeError) return OFFLINE_UPSTREAM;
    console.debug('[provision]', error);
    return GENERIC_MESSAGE;
  };

  /* ── crypto helpers (CSPRNG, unbiased) ───────────────────────────────────── */

  /**
   * Uniform integer in [0, maxExclusive) via rejection sampling.
   * @param {number} maxExclusive
   * @returns {number}
   */
  const randomInt = (maxExclusive) => {
    const limit = Math.floor(0x1_0000_0000 / maxExclusive) * maxExclusive;
    const buffer = new Uint32Array(1);
    let value = 0;
    do {
      crypto.getRandomValues(buffer);
      value = buffer[0];
    } while (value >= limit);
    return value % maxExclusive;
  };

  /**
   * @param {number} length
   * @returns {string}
   */
  const randomDigits = (length) =>
    Array.from({ length }, () => randomInt(10)).join('');

  /** Ambiguous glyphs (l, I, O, 0, 1) removed to survive manual transcription. */
  const SECRET_ALPHABET =
    'abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789!#$%&*+-=?@^_';

  /**
   * NIST SP 800-63B keeps composition rules optional and leans on length:
   * 20 characters from a 74-symbol alphabet (about 124 bits).
   */
  const generateSecret = () => {
    let secret = '';
    for (let index = 0; index < 20; index += 1) {
      secret += SECRET_ALPHABET[randomInt(SECRET_ALPHABET.length)];
    }
    return secret;
  };

  /* ── serialization helpers ───────────────────────────────────────────────── */

  /**
   * @param {unknown} value
   * @returns {string}
   */
  const escapeHtml = (value) =>
    String(value).replace(/[&<>"']/g, (character) => ({
      '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
    })[character]);

  /**
   * Normalises epoch seconds, epoch millis, Firestore {_seconds|seconds} or an
   * ISO string into ISO 8601. Stale values resolve to null.
   * @param {unknown} value
   * @returns {string|null}
   */
  const toIsoTimestamp = (value) => {
    const raw = value && typeof value === 'object'
      ? /** @type {any} */ (value)._seconds
        ?? /** @type {any} */ (value).seconds
        ?? /** @type {any} */ (value).value
      : value;
    if (raw === null || raw === undefined) return null;

    const numeric = typeof raw === 'string' && /^\d+$/.test(raw.trim())
      ? Number(raw)
      : raw;

    let date = null;
    if (typeof numeric === 'number' && Number.isFinite(numeric)) {
      date = new Date(numeric < 1e11 ? numeric * 1_000 : numeric);
    } else if (typeof numeric === 'string') {
      date = new Date(numeric);
    }
    if (!date || Number.isNaN(date.getTime())) return null;

    return date.getTime() > Date.now() - STALE_WINDOW_MS
      ? date.toISOString()
      : null;
  };

  /**
   * Depth-first scan for the first recognised expiry field.
   * @param {unknown} payload
   * @returns {string|null}
   */
  const extractExpiry = (payload) => {
    let found = null;
    /** @param {unknown} node */
    const visit = (node) => {
      if (found || !node || typeof node !== 'object') return;
      if (Array.isArray(node)) {
        node.forEach(visit);
        return;
      }
      for (const [key, value] of Object.entries(node)) {
        if (EXPIRY_KEYS.includes(key)) {
          found = toIsoTimestamp(value);
          if (found) return;
        }
        visit(value);
      }
    };
    visit(payload);
    return found;
  };

  /* ── transport ───────────────────────────────────────────────────────────── */

  /**
   * AbortSignal.timeout is Baseline since April 2024; the fallback covers
   * pre-2024 WebViews.
   * @param {number} timeoutMs
   * @returns {AbortSignal}
   */
  const createTimeoutSignal = (timeoutMs) => {
    if (typeof AbortSignal.timeout === 'function') {
      return AbortSignal.timeout(timeoutMs);
    }
    const controller = new AbortController();
    setTimeout(() => controller.abort(), timeoutMs);
    return controller.signal;
  };

  /**
   * POSTs JSON and throws ApiError on non-2xx responses.
   * @param {string} url
   * @param {unknown} payload
   * @param {Record<string, string>} headers
   * @returns {Promise<any>}
   */
  const postJson = async (url, payload, headers) => {
    const response = await fetch(url, {
      method: 'POST',
      headers,
      body: JSON.stringify(payload),
      signal: createTimeoutSignal(REQUEST_TIMEOUT_MS),
    });

    const text = await response.text();
    let body;
    try {
      body = JSON.parse(text);
    } catch {
      body = { raw: text };
    }

    if (!response.ok) {
      const detail = body?.error?.message ?? body?.error?.status ?? body?.raw;
      const message = detail === undefined ? \`HTTP \${response.status}\` : detail;
      const code = /^([A-Z][A-Z0-9_]+)\s*:/.exec(String(message))?.[1] ?? 'HTTP_ERROR';
      throw new ApiError(
        typeof message === 'string' ? message : JSON.stringify(message),
        { code, status: response.status },
      );
    }
    return body;
  };

  /* ── upstream operations ─────────────────────────────────────────────────── */

  /**
   * @param {string} identifier
   * @returns {Promise<boolean>} the registered flag from createAuthUri.
   */
  const isRegistered = async (identifier) => {
    const body = await postJson(
      CREATE_AUTH_URI_ENDPOINT,
      { identifier, continueUri: ${JSON.stringify(CONTINUE_URI)} },
      CLIENT_HEADERS,
    );
    return body?.registered === true;
  };

  /**
   * Enrolls the account, retrying once when a parallel signup won the race.
   * @param {string} address
   * @returns {Promise<string>} Firebase ID token
   */
  const enrollAccount = async (address) => {
    const password = generateSecret();
    for (let attempt = 0; ; attempt += 1) {
      try {
        const body = await postJson(
          SIGN_UP_ENDPOINT,
          { email: address, password, returnSecureToken: true },
          CLIENT_HEADERS,
        );
        return body.idToken;
      } catch (error) {
        const retryable = error instanceof ApiError && error.code === 'EMAIL_EXISTS';
        if (attempt > 0 || !retryable) throw error;
      }
    }
  };

  /**
   * @typedef {{ orderId: string, expiresAt: string|null }} Entitlement
   */

  /**
   * Verifies every purchase token concurrently and keeps the longest entitlement.
   * @param {string} idToken
   * @returns {Promise<Entitlement>}
   */
  const grantEntitlement = async (idToken) => {
    const headers = { ...VERIFIER_HEADERS, authorization: \`Bearer \${idToken}\` };

    const attempts = PURCHASE_TOKENS.map((token) => {
      const orderId = [
        ORDER_ID_PREFIX,
        randomDigits(4),
        randomDigits(4),
        randomDigits(5),
      ].join('-');
      return postJson(
        VERIFY_PURCHASE_ENDPOINT,
        { data: { productId: PRODUCT_ID, token, skuType: SKU_TYPE, orderId } },
        headers,
      ).then((payload) => ({ orderId, expiresAt: extractExpiry(payload) }));
    });

    const settled = await Promise.allSettled(attempts);
    const entitlements = settled
      .filter((result) => result.status === 'fulfilled')
      .map((result) => /** @type {Entitlement} */ (result.value));

    if (entitlements.length === 0) {
      const rejection = settled.find((result) => result.status === 'rejected');
      throw /** @type {any} */ (rejection)?.reason
        ?? new ApiError('All purchase tokens were rejected.', { code: 'PURCHASE_REJECTED' });
    }

    /** @param {Entitlement} entitlement */
    const weight = (entitlement) =>
      entitlement.expiresAt ? Date.parse(entitlement.expiresAt) : 0;

    return entitlements.sort((left, right) => weight(right) - weight(left))[0];
  };

  /* ── DOM bindings ────────────────────────────────────────────────────────── */

  /** @param {string} id */
  const byId = (id) => document.getElementById(id);

  const VIEWS = Object.freeze({
    email: byId('view-email'),
    confirm: byId('view-confirm'),
    success: byId('view-success'),
  });

  const lookupForm = byId('lookup-form');
  const statusRegion = byId('status-region');
  const emailInput = byId('email');
  const confirmSlot = byId('confirm-slot');
  const submitButton = byId('submit-email');
  const restartButton = byId('restart');
  const expiresAtCell = byId('expires-at');
  const orderIdCell = byId('order-id');

  const DATE_FORMATTER = new Intl.DateTimeFormat('en', { dateStyle: 'long' });

  let isBusy = false;

  /* ── view layer ──────────────────────────────────────────────────────────── */

  /** @param {keyof typeof VIEWS} name */
  const showView = (name) => {
    for (const [key, view] of Object.entries(VIEWS)) {
      view.classList.toggle('is-hidden', key !== name);
    }
  };

  /**
   * Retints the ambient gradient; null restores the neutral palette.
   * @param {'success'|'warning'|'danger'|null} severity
   */
  const setAccent = (severity) => {
    if (severity) document.documentElement.dataset.accent = severity;
    else delete document.documentElement.dataset.accent;
  };

  /**
   * Renders one status message. Failures announce assertively, progress politely.
   * @param {'success'|'warning'|'danger'|null} severity
   * @param {string} [text]
   */
  const renderMessage = (severity, text = '') => {
    statusRegion.replaceChildren();
    statusRegion.setAttribute('aria-live', severity === 'danger' ? 'assertive' : 'polite');
    setAccent(severity);
    if (!severity) return;

    const box = document.createElement('div');
    box.className = \`alert alert--\${severity}\`;
    box.textContent = text;
    statusRegion.append(box);
  };

  /** @param {boolean} invalid */
  const setInvalid = (invalid) => {
    emailInput.setAttribute('aria-invalid', String(invalid));
  };

  /**
   * @param {HTMLButtonElement} button
   * @param {boolean} busy
   */
  const setBusy = (button, busy) => {
    isBusy = busy;
    button.disabled = busy;
    button.setAttribute('aria-busy', String(busy));
  };

  /** @param {string|null} iso */
  const formatDate = (iso) => {
    if (!iso) return UNKNOWN_DATE_LABEL;
    const date = new Date(iso);
    return Number.isNaN(date.getTime()) ? UNKNOWN_DATE_LABEL : DATE_FORMATTER.format(date);
  };

  /** @param {Entitlement} entitlement */
  const showSuccess = ({ orderId, expiresAt }) => {
    expiresAtCell.textContent = formatDate(expiresAt);
    orderIdCell.textContent = orderId;
    statusRegion.replaceChildren();
    setAccent('success');
    showView('success');
    emailInput.value = '';
  };

  const resetFlow = () => {
    showView('email');
    renderMessage(null);
    confirmSlot.replaceChildren();
    emailInput.focus();
  };

  /**
   * One precondition view serving both lookup outcomes.
   * @param {string} address
   * @param {boolean} registered
   */
  const renderPrecondition = (address, registered) => {
    const variant = registered ? 'warning' : 'success';
    const verdict = registered
      ? 'is already registered at the identity provider.'
      : 'has no account at the identity provider.';
    const hint = registered
      ? '<p class="note" style="margin:0 0 4px">Precondition: delete that account in the Alight Motion app, then look it up again. Provisioning aborts while the account persists.</p>'
      : '<p class="note" style="margin:0 0 4px">Proceeding enrolls the account and grants the entitlement.</p>';
    const primaryLabel = registered ? 'Look up again' : 'Provision';
    const secondaryLabel = 'Change identifier';

    confirmSlot.innerHTML =
      \`<div class="notice notice--\${variant}"><span class="notice__dot"></span>\` +
      \`<p><b>\${escapeHtml(address)}</b> \${verdict}</p></div>\` +
      hint +
      \`<button id="confirm-continue" type="button">\${primaryLabel}</button>\` +
      \`<button class="ghost" id="confirm-change" type="button">\${secondaryLabel}</button>\`;

    byId('confirm-change').addEventListener('click', resetFlow);

    const continueButton = /** @type {HTMLButtonElement} */ (byId('confirm-continue'));
    continueButton.addEventListener('click', () => {
      if (isBusy) return;
      provision(address, registered, continueButton);
    });
  };

  /* ── orchestration ───────────────────────────────────────────────────────── */

  /**
   * Re-checks when required, enrolls the account, grants the entitlement,
   * then reports the result.
   * @param {string} address
   * @param {boolean} registered
   * @param {HTMLButtonElement} button
   */
  const provision = async (address, registered, button) => {
    setBusy(button, true);
    renderMessage(null);
    try {
      if (registered) {
        if (await isRegistered(address)) {
          renderMessage('warning', 'Precondition unmet: the account still exists at the identity provider. Delete it in the app, then look it up again.');
          return;
        }
        renderPrecondition(address, false);
      }

      renderMessage(null, 'Enrolling account…');
      const idToken = await enrollAccount(address);

      renderMessage(null, 'Verifying purchase token…');
      const entitlement = await grantEntitlement(idToken);

      showSuccess(entitlement);
    } catch (error) {
      renderMessage('danger', describeError(error));
    } finally {
      setBusy(button, false);
    }
  };

  /* ── entry point ─────────────────────────────────────────────────────────── */

  /** @param {SubmitEvent} event */
  const handleSubmit = async (event) => {
    event.preventDefault();
    if (isBusy) return;

    const address = emailInput.value.trim().toLowerCase();
    emailInput.value = address;
    setInvalid(false);

    if (!address) {
      setInvalid(true);
      renderMessage('warning', 'Identifier is required.');
      emailInput.focus();
      return;
    }
    if (!EMAIL_PATTERN.test(address)) {
      setInvalid(true);
      renderMessage('danger', 'Identifier is not a syntactically valid address (expected user@example.com).');
      emailInput.focus();
      return;
    }
    if (!navigator.onLine) {
      renderMessage('warning', OFFLINE_CLIENT);
      return;
    }

    renderMessage(null, 'Looking up identifier…');
    setBusy(submitButton, true);
    try {
      const registered = await isRegistered(address);
      renderPrecondition(address, registered);
      showView('confirm');
      renderMessage(null);
    } catch (error) {
      renderMessage('danger', describeError(error));
    } finally {
      setBusy(submitButton, false);
    }
  };

  /* ── listeners ───────────────────────────────────────────────────────────── */

  lookupForm.addEventListener('submit', handleSubmit);
  restartButton.addEventListener('click', resetFlow);
  emailInput.addEventListener('input', () => {
    setInvalid(false);
    if (statusRegion.firstChild) renderMessage(null);
  });
})();
</script>
</body>
</html>`;

const SECURITY_HEADERS = {
  'content-type': 'text/html; charset=utf-8',
  'cache-control': 'public, max-age=0, must-revalidate',
  'content-security-policy': [
    "default-src 'none'",
    "base-uri 'none'",
    "form-action 'self'",
    "frame-ancestors 'none'",
    "img-src 'self' data:",
    "style-src 'unsafe-inline'",
    "script-src 'unsafe-inline'",
    'connect-src https://identitytoolkit.googleapis.com https://us-central1-alight-creative.cloudfunctions.net',
  ].join('; '),
  'cross-origin-opener-policy': 'same-origin',
  'referrer-policy': 'no-referrer',
  'strict-transport-security': 'max-age=31536000; includeSubDomains',
  'x-content-type-options': 'nosniff',
};

export default {
  /**
   * @param {Request} request
   * @returns {Promise<Response>}
   */
  async fetch(request) {
    const url = new URL(request.url);

    if (url.pathname === '/amprem/' || url.pathname === '/amprem/index.html') {
      return Response.redirect(`${url.origin}/amprem`, 301);
    }

    if (url.pathname !== '/amprem') {
      return new Response('Not Found', {
        status: 404,
        headers: { 'content-type': 'text/plain; charset=utf-8' },
      });
    }

    return new Response(PAGE, { headers: SECURITY_HEADERS });
  },
};
--93e64d81176e9dc61269b6e9e73145f35b623e4921150b6f4a93b5410ab3--
