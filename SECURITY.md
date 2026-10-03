# Security policy

**English** · [简体中文](SECURITY.zh-CN.md)

McBridge puts a shared secret and an HTTP API between a Minecraft server and a Flarum
forum, so a defect here is usually a defect in that trust boundary. Reports are welcome and
will be taken seriously.

## Reporting a vulnerability

**Please do not open a public issue for a security problem.** Use GitHub's private
reporting instead:

1. open the [repository](https://github.com/StalirMC/mc-flarum-bridge) → **Security** →
   **Report a vulnerability** (GitHub Security Advisories), or
2. use the **Report a vulnerability** button on the **Security** tab.

That gives us a private thread. Useful reports contain:

- the affected version (`/mcbridge stats` on the game server, or
  `composer show stalirmc/mc-flarum-bridge` on the forum),
- the platform (Paper / Folia / NeoForge, with the Minecraft version),
- what an attacker gains, and the concrete request or chat command that demonstrates it,
- whether a valid `security.secret` was needed to pull it off.

Please do not include a working `security.secret`, session cookies or player personal data
in a report. If you must show traffic, replace them.

There is no bug bounty and no response-time guarantee; this is a small project maintained
in its spare time. Confirmed problems are fixed in the next release and credited in the
release notes unless you ask otherwise.

## Supported versions

Only the newest release is supported. Fixes ship as a new `0.0.x` and are **not** backported
to older tags. The Flarum extension and the jar have to be on the same version: a mismatched
pair is the most common cause of "the bridge suddenly does nothing".

## What the trust boundary is

Worth understanding before reporting, so a report lands on the right layer:

- Every machine request is signed with HMAC-SHA256 over
  `{timestamp}\n{nonce}\n{METHOD}\n{path}\n{body}` using `security.secret`. The secret is
  generated on the forum side (`php flarum mc-bridge:secret`) and pasted into the game
  server's config file.
- A request is rejected when the timestamp is outside a **±5 minute** window, when the
  nonce was already used (kept for **10 minutes**), or when the signature does not match.
  The comparison is constant-time.
- The signed path is the bridge path, not the raw request path, so a subdirectory install
  (`https://forum.example.com/forum`) does not break signing or smuggle a path.
- The machine endpoints live under `/api/mc-bridge/` and are CSRF-exempt; they are
  authenticated by the signature rather than by a session.
- In-game permissions are separate: `mcbridge.bind` and `mcbridge.report` default to
  everyone, `mcbridge.admin` defaults to operators.

## Hardening checklist for operators

- Serve the forum over **HTTPS** and set `forum.url` to the `https://` URL. The signature
  proves authenticity and integrity, not confidentiality: over plain HTTP an eavesdropper
  can read every report body (player names and, if enabled, chat transcripts) and can
  replay a captured request once inside the 5 minute window.
- Give `security.secret` real entropy (the generated value is fine) and treat it like a
  password: keep it out of git, out of screenshots and out of support requests.
- **Rotate the secret if it leaks**: run `php flarum mc-bridge:secret` again and update the
  game server's config, then `/mcbridge reload`. Rotation invalidates nothing else.
- Keep the extension and the jar on the same version, and keep the forum itself patched.
- Limit who can read the game server's config file and the forum's `.env`; both sides hold a
  copy of the secret.
- Chat context is captured from **public chat only**, and it is stored on the forum as part
  of a report. `report.chat-context-lines` (default 10, max 500) and the `report.chat-context`
  switch control it; set the switch to `0` if your players' privacy expectations (or local
  law) require no transcript at all. Private messages and commands are never captured.

## Things that are not treated as vulnerabilities

- Anything that requires an already-compromised forum admin account, shell on the forum
  host, console access on the Minecraft server, or direct database access. At that point the
  attacker owns a copy of the secret anyway.
- A leaked secret used to call the machine endpoints. That is a key rotation, not a CVE:
  rotate as above.
- The plugin reporting `security.secret` as missing, or pausing its features, on a fresh
  install. That is the intended fail-closed behaviour.
- Missing features that only affect how something is displayed.
- Denial of service through sheer volume against a deployment that is not rate limited at
  its reverse proxy. There is no built-in rate limiter (see the "what could be added" notes);
  put one in front of the forum.
