# clj-infisical — Requirements Spec (v1)

Status: draft, no implementation yet. This document is the input for writing
tests; no production code should be written until the scenarios in
[§8 Test Scenarios](#8-test-scenarios) are agreed.

## 1. Scope

**In scope (v1):**

- Authenticate to Infisical using [Universal Auth](https://infisical.com/docs/documentation/platform/identities/universal-auth)
  (client id + client secret → short-lived access token).
- Resolve the client id / client secret from environment variables or from
  files under `/etc/infisical`, with strict permission checks.
- Fetch a single secret's value given a project id, environment, and secret
  path.
- A small, explicit error taxonomy so callers can pattern-match on failure
  reasons instead of parsing strings.

**Out of scope (v1) — explicitly deferred:**

- Writing/updating/deleting secrets.
- Listing/bulk-fetching secrets, folders, secret imports, dynamic secrets.
- Token caching/reuse across calls (every `get-secret!` call performs its own
  login). Caching is a pure optimization with a real correctness risk
  (expiry, thread-safety) — not justified until there's a measured need.
- Auth methods other than Universal Auth (AWS/K8s/OIDC/etc.).
- Custom retry/backoff logic — network-failure retries are `clj-http-lite`'s
  problem, not this library's (see §2).
- Windows file-permission semantics (POSIX only).
- This library is intended for publication to Clojars (see §10); v1 scope
  above is unaffected by that, it's a packaging concern only.

## 2. Dependencies

- [`clj-http-lite`](https://github.com/hozumi/clj-http-lite) — all HTTP.
- `org.clojure/data.json` — all JSON encode/decode.
- Clojure core / `clojure.java.io` preferred everywhere else.
- Exception: POSIX file permission/ownership bits have no pure-Clojure
  accessor. Reading them requires `java.nio.file` interop
  (`Files/getPosixFilePermissions`, `Files/getOwner`). This is the one place
  we reach past core Clojure, and only from the single action responsible for
  stat'ing `/etc/infisical`.

No other runtime dependency should be needed for v1.

**Retry:** confirmed — network-failure retries are handled by `clj-http-lite`
itself (its own timeout/connection-failure behavior), not reimplemented here.
Note for implementation: as of the current `clj-commons/clj-http-lite`
user guide, the library exposes `:conn-timeout`/`:socket-timeout` options but
has no built-in retry-on-failure handler — "use clj-http-lite's own retry
logic" in practice means *this library adds none*, a single request attempt
per call, and a connection failure simply propagates as an uncaught
`java.io.IOException`-family exception rather than being caught and wrapped.
If retries turn out to be wanted later, that's a caller-side concern (wrap
`get-secret!` in retry logic) rather than something `clj-infisical` should
own — revisit if that assumption is wrong.

## 3. Infisical API contract (verified against a working curl script against a real self-hosted instance)

### 3.1 Universal Auth login

```
POST {site-url}/api/v1/auth/universal-auth/login
Content-Type: application/json

{"clientId": "<client-id>", "clientSecret": "<client-secret>"}
```

Response `200`:

```json
{
  "accessToken": "...",
  "expiresIn": 7200,
  "accessTokenMaxTTL": 7200,
  "tokenType": "Bearer"
}
```

Non-200 (e.g. `400`/`401`) → authentication failure. Body is JSON with an
error message; treat any non-2xx as `:clj-infisical/auth-failed` (§7).

### 3.2 Fetch a secret

```
GET {site-url}/api/v3/secrets/raw/{secret-name}?workspaceId=...&secretPath=...&environment=...
Authorization: Bearer <access-token>
```

- `workspaceId` — required. (Infisical's UI/newer docs call this concept
  "project"; the `/api/v3/secrets/raw` endpoint's query parameter is
  `workspaceId`, so this library uses that name to match the wire format
  exactly rather than translating it.)
- `environment` — required by this library (defaulted, see §5.6) so callers
  always know which environment they read.
- `secretPath` — optional, defaults to `/`.
- No `viewSecretValue` param on this endpoint — the `raw` path already
  returns the plaintext value directly.

Response `200`:

```json
{
  "secret": {
    "secretValue": "..."
  }
}
```

The `raw` endpoint's full response may carry more fields (`secretKey`,
`version`, etc., by analogy with other Infisical secret endpoints) beyond
the confirmed `secret.secretValue`. **Revised from the earlier draft:**
rather than discarding those extra fields, `parse-secret-response` (§5.5)
passes the whole `secret` object through, keywordizing keys, with only
`secretValue`/`:secret-value` treated as guaranteed-present. This is what
makes the "raw" half of §5.6 possible.

`404` → secret not found → `:clj-infisical/secret-not-found`.
Other non-2xx → `:clj-infisical/http-error`. For any non-2xx, the response
body is also JSON-decoded when possible and kept on the thrown error (§7) —
Infisical's own error bodies typically carry a `message`/`error` field, and
this library shouldn't force callers to re-parse the raw body string to get
at that.

## 4. Naming convention (Action vs Calculation vs Data)

Per the ACD model, every var in the library is one of:

- **Action** — name ends in `!` (`login!`, `fetch-secret!`, `read-file!`,
  `stat!`). Result depends on I/O, the network, or the clock. Not safe to
  call twice and expect the same result, or to call from a `map`.
- **Calculation** — plain name, no `!`. Pure function of its arguments.
  Same input → same output, forever, no side effects. Safe to unit-test with
  plain data, no mocking.
- **Data** — plain maps/records, no behavior. Passed between calculations
  and actions.

Every action in this library is a *thin* wrapper: gather raw I/O results,
hand them to a calculation, return what the calculation decided. No action
should contain a business decision (a conditional that changes program
behavior based on content, not just on success/failure of I/O).

## 5. Namespaces and functions

### 5.1 `clj-infisical.data` — Data

Plain maps, no defrecord (keeps callers free to use normal map functions).
Documented here as shapes, not code.

| Name | Shape |
|---|---|
| `Credentials` | `{:client-id string, :client-secret string, :source #{:env :file}}` |
| `Config` | `{:workspace-id string, :environment string, :secret-path string, :site-url string}` |
| `AccessToken` | `{:token string, :expires-in int, :token-type string}` |
| `Secret` | The response's `secret` object, keywordized, passed through as-is. `:secret-value` is the only key this library guarantees (throws `:invalid-response` if absent); every other key Infisical happens to include (`:secret-key`, `:version`, ...) rides along unexamined. |
| `ErrorData` | `{:type keyword, :status int, :body string, :parsed (map or nil)}` — `:parsed` is the JSON-decoded response body when it decodes, else `nil`. Carried into the thrown `ex-info`'s `ex-data` (§7) so callers can read Infisical's own error `message` without re-parsing `:body` themselves. |

### 5.2 `clj-infisical.credentials` — resolving client id/secret

**Calculations:**

- `select-credential-source [inputs] -> Credentials | ErrorData`
  Pure decision function. `inputs` is a plain map assembled by the action
  below (never touches the filesystem or env itself):

  ```clojure
  {:env {:client-id "..." :client-secret "..."}   ; nil values allowed
   :file {:dir-exists? bool
          :dir-symlink? bool
          :dir-not-group-or-other-writable? bool
          :dir-owned-by-root? bool
          :client-id-file {:exists? bool :symlink? bool
                            :no-group-or-other-bits? bool
                            :owned-by-process-user? bool
                            :content "..."}
          :client-secret-file {...same shape...}}}
  ```

  Precedence and rules (all pure, all unit-testable from this input map
  alone):

  1. If both `env.client-id` and `env.client-secret` are non-blank →
     `{:source :env, ...}`. Env wins outright; files are not consulted.
  2. If exactly one of `env.client-id` / `env.client-secret` is set →
     `:clj-infisical/ambiguous-credentials` error (partial env config is
     almost certainly a typo'd deploy, fail loudly rather than silently
     falling through to files).
  3. If neither env var is set, fall through to files. Confirmed ownership
     model: **`/etc/infisical` is owned by root**; **each credential file is
     owned by the process's own effective user** (a different owner than
     the directory — root manages the directory, the running service owns
     its own secret file). Checked in this order:
     1. If **both** `client-id-file.exists?` and `client-secret-file.exists?`
        are `false` → `:clj-infisical/credentials-not-found`. Nothing is
        configured; that's absence, not a misconfiguration, so it's checked
        *before* any security predicate runs (a directory that doesn't
        exist can't meaningfully be "insecure").
     2. Otherwise, check every one of the following; if **any** fails →
        `:clj-infisical/insecure-credential-files`, naming the failing
        path and check:
        - **Directory**: `dir-exists?`, not `dir-symlink?`, `dir-owned-by-root?`,
          and `dir-not-group-or-other-writable?` (`mode & 0o022 == 0`).
          Group/other **read**/**execute** on the directory is fine and
          expected — it only exposes filenames, not secret contents, and
          the process (running as a non-root user) needs to be able to
          traverse it at all. This mirrors OpenSSH's own check on
          `~/.ssh`: SSH rejects a `.ssh` directory or key file that's
          group/other-*writable*, but does not require the directory
          itself to be unreadable by others — confirmed as the intended
          model.
        - **Each file** (`client_id`, `client_secret`): `exists?`, not
          `symlink?`, `owned-by-process-user?`, and
          `no-group-or-other-bits?` (`mode & 0o077 == 0`, i.e. `0600` or
          stricter) — these hold the actual secret bytes, so they get the
          strict check. (This also covers the "exactly one of the two
          files exists" case — a genuinely partial/inconsistent setup is
          reported as insecure/misconfigured, not as "not found".)
        **Fail closed** — never silently skip an insecure file and report
        "not found" instead, because that hides a misconfiguration from
        the operator.
     3. If every check passes, trim trailing newline/whitespace from file
        contents and return `{:source :file, ...}`.

- `bits-clear? [mode-bits mask] -> bool` — `(zero? (bit-and mode-bits mask))`.
  One general helper used with two different masks (`8r022` for the
  directory's write check, `8r077` for each file's full check), rather than
  two near-duplicate functions. Extracted as its own calculation because
  it's the one bit of genuinely hidden logic (a "magic number") worth
  naming and testing in isolation.
- `read-env [env-map] -> {:client-id ..., :client-secret ...}` — pulls
  `"INFISICAL_CLIENT_ID"`/`"INFISICAL_CLIENT_SECRET"` out of an already-read
  environment map. Split out from `read-env!` (below) purely so the
  "which two keys do we care about" logic is unit-testable with a plain map
  literal instead of needing real process environment variables (which a
  JVM test process can't set for itself mid-run).

**Actions:**

- `read-env! [] -> {:client-id ..., :client-secret ...}` — thin wrapper,
  `(read-env (System/getenv))`. `System/getenv` returns a `java.util.Map`,
  which `get`/destructuring handle fine without conversion.
- `stat-credential-files! [dir] -> inputs-shaped map (§ above, :file key only)` —
  does all filesystem interop (existence, POSIX permissions, owner,
  symlink check, file read) for `dir/client_id` and `dir/client_secret`, and
  returns it as data. No decisions made here.
- `resolve-credentials! [] -> Credentials | (throws ex-info)` — calls
  `read-env!` and `stat-credential-files!` (short-circuiting: does not stat
  the filesystem if env vars are already valid, since §5.2 rule 1 makes it
  irrelevant — this is a small optimization but also avoids requiring
  `/etc/infisical` to exist at all in the common env-var deployment case),
  builds the `inputs` map, calls `select-credential-source`, and either
  returns `Credentials` or throws `ex-info` from the error data.

Credential file paths: `/etc/infisical/client_id` and
`/etc/infisical/client_secret` — confirmed.

### 5.3 `clj-infisical.http` — thin transport actions

- `post-json! [url json-body-map] -> {:status int, :body string}` — wraps
  `clj-http-lite.client/post` with `Content-Type: application/json` and
  `(json/write-str json-body-map)` as the body, always
  `{:throw-exceptions false}` (the library decides what's an error, not
  `clj-http-lite`).
- `get-json! [url query-params headers] -> {:status int, :body string}` —
  wraps `clj-http-lite.client/get`, same non-throwing contract.

These two functions are the *only* things in the library allowed to import
`clj-http-lite`. Everything else depends on them, not on the HTTP library
directly — that's the boundary that lets §5.4/§5.5 calculations be tested
with plain maps instead of a mocked HTTP client.

### 5.4 `clj-infisical.auth` — Universal Auth login

**Calculations:**

- `login-request [site-url client-id client-secret] -> {:url ..., :json-body-map ...}`
  Builds the request map for `post-json!` — `json-body-map` is
  `{"clientId" client-id "clientSecret" client-secret}`. Pure string/map
  assembly.
- `parse-login-response [{:keys [status body]}] -> AccessToken | ErrorData`
  Pure: `data.json/read-str` the body, and on `200` pull out
  `accessToken`/`expiresIn`/`tokenType` into `AccessToken` (§5.1); on any
  other status, return `:clj-infisical/auth-failed` `ErrorData` (§5.1)
  carrying `:status`, `:body`, and `:parsed` (the body JSON-decoded, or
  `nil` if it doesn't parse) so callers can read Infisical's own
  `message`/`error` fields directly.

**Actions:**

- `login! [site-url ^Credentials creds] -> AccessToken | (throws ex-info)`
  `(-> (login-request site-url (:client-id creds) (:client-secret creds))
       post-json!
       parse-login-response
       ...)`, throwing on error data. Thin wrapper, no decisions.

### 5.5 `clj-infisical.secrets` — fetching a secret

**Calculations:**

- `secret-request [^Config config ^AccessToken token secret-name] -> {:url ..., :query-params ..., :headers ...}`
  Pure request assembly per §3.2: `url` is
  `{site-url}/api/v3/secrets/raw/{secret-name}`, `query-params` is
  `{"workspaceId" ... "environment" ... "secretPath" ...}`.
- `parse-secret-response [{:keys [status body]}] -> Secret | ErrorData`
  Pure: `200` → keywordize the whole `secret` object into `Secret` (§5.1),
  passing every key through, requiring only `:secret-value` to be present;
  `404` → `:clj-infisical/secret-not-found`; other non-2xx →
  `:clj-infisical/http-error`; unparsable JSON, or a `200` body missing
  `secret.secretValue` → `:clj-infisical/invalid-response`. Every `ErrorData`
  branch carries `:status`/`:body`/`:parsed` per §5.1, same as
  `parse-login-response`.

**Actions:**

- `fetch-secret! [^Config config ^AccessToken token secret-name] -> Secret | (throws ex-info)`
  Thin wrapper: `secret-request` → `get-json!` → `parse-secret-response` →
  throw-or-return.

### 5.6 `clj-infisical.core` — public facade

Two public entry points, sharing one private orchestration action so neither
duplicates credential resolution / login / fetch:

- `-fetch-secret! [{:keys [workspace-id environment secret-path secret-name
                            site-url client-id client-secret]
                     :or {environment "dev"
                          secret-path "/"
                          site-url "https://app.infisical.com"}}]
   -> Secret | (throws ex-info)`
  (private, `-` prefix; not part of the public API). Orchestration only (an
  action composed of other actions — no new decisions of its own):

  1. `workspace-id` and `secret-name` are required; missing either throws
     `:clj-infisical/invalid-arguments` before any I/O happens (cheap
     calculation-style guard, checked first on purpose).
  2. If `client-id`/`client-secret` are both supplied in the argument map,
     use them directly as `Credentials` (`:source :explicit`) — this is the
     seam that lets *callers* of this library unit-test their own code
     against `clj-infisical` without env vars or `/etc/infisical` existing.
     Otherwise call `resolve-credentials!` (§5.2).
  3. `login!` with those credentials.
  4. `fetch-secret!` for `secret-name`, returning the full `Secret` map.

- `get-secret-raw! [args] -> Secret` — `(-fetch-secret! args)`, unchanged.
  The "raw" half requested: the whole decoded `secret` object (§5.1),
  keywordized keys, every field Infisical returned — not just the value.
  Useful for the same reason the original curl script piped through `jq`
  rather than assuming only `secretValue` existed: callers may want
  `:version`, or other fields, without this library deciding in advance
  they don't matter.
- `get-secret! [args] -> string` — `(:secret-value (-fetch-secret! args))`.
  The convenience wrapper most callers use — equivalent to
  `jq -r '.secret.secretValue'` on top of `get-secret-raw!`.

Both are thin: the only "decision" either makes is `get-secret!`'s trivial
key pluck, which is itself pure and needs no separate calculation function
to justify unit-testing it in isolation.

## 6. Environment variables

| Variable | Required | Meaning |
|---|---|---|
| `INFISICAL_CLIENT_ID` | no (see §5.2) | Universal Auth client id |
| `INFISICAL_CLIENT_SECRET` | no (see §5.2) | Universal Auth client secret |

`workspace-id`, `environment`, `secret-path`, `site-url` are **not** read
from the environment in v1 — they're per-call arguments, because this
library is meant to be embedded in other projects that may talk to multiple
workspaces/environments (and, per the corrected §3, is regularly used
against self-hosted instances, so `site-url` in particular must not silently
default to Infisical Cloud in a way a caller could forget to override).
Ambient config for those would be a footgun (silent cross-environment
reads). Revisit only if real usage shows this is annoying.

## 7. Error taxonomy

All errors are `ex-info` thrown with `(ex-message ex)` human-readable and
`(ex-data ex)` always carrying `:type`, so callers can
`(case (:type (ex-data ex)) ...)` instead of parsing strings. What else rides
along in `ex-data` depends on the error's origin (this is what §5.1's
`ErrorData` shape describes):

- **HTTP-originated** (`auth-failed`, `secret-not-found`, `http-error`,
  `invalid-response`): `:status`, `:body` (raw response body string), and
  `:parsed` (the body JSON-decoded when possible, else `nil`) — this is the
  mechanism for reading Infisical's actual `message`/`error` fields on a
  400/500 without re-parsing anything yourself.
- **Credential-resolution-originated** (`credentials-not-found`,
  `ambiguous-credentials`, `insecure-credential-files`): no HTTP response
  exists yet, so instead carries whatever's relevant to the failure, e.g.
  `:path` and `:reason` for `insecure-credential-files` (naming which
  check on which file/dir failed).
- **Argument-validation-originated** (`invalid-arguments`): `:missing-keys`.

| `:type` | Thrown by | Meaning |
|---|---|---|
| `:clj-infisical/invalid-arguments` | `get-secret!`, `get-secret-raw!` | Missing `workspace-id` or `secret-name` |
| `:clj-infisical/credentials-not-found` | `resolve-credentials!` | No env vars, no usable files |
| `:clj-infisical/ambiguous-credentials` | `resolve-credentials!` | Exactly one of the two env vars set |
| `:clj-infisical/insecure-credential-files` | `resolve-credentials!` | Dir/file exists but fails permission/owner/symlink check; `:reason` names which |
| `:clj-infisical/auth-failed` | `login!` | Non-2xx from Universal Auth login |
| `:clj-infisical/secret-not-found` | `fetch-secret!` | `404` from secret read |
| `:clj-infisical/http-error` | `login!`, `fetch-secret!` | Any other non-2xx |
| `:clj-infisical/invalid-response` | `login!`, `fetch-secret!` | 2xx but body isn't the JSON shape expected |

## 8. Test Scenarios

Given/When/Then list to drive `deftest` authoring, grouped by namespace.
Everything under "Calculations" needs no mocking — plain data in, plain data
(or thrown ex-info) out. Everything under "Actions" needs the corresponding
boundary function rebound (`with-redefs`) to fake I/O, since real
`clj-http-lite`/filesystem/env calls are out of scope for unit tests.

### 8.1 `clj-infisical.credentials` — calculations

- `bits-clear?`
  - Given `8r700` and mask `8r077`, when checked, then `true`.
  - Given `8r600` and mask `8r077`, when checked, then `true`.
  - Given `8r750` and mask `8r077`, when checked, then `false` (group bits
    set).
  - Given `8r704` and mask `8r077`, when checked, then `false` (other-read
    bit set).
  - Given `8r755` and mask `8r022`, when checked, then `true` (group/other
    *read+execute* is fine under the directory's write-only mask).
  - Given `8r775` and mask `8r022`, when checked, then `false` (group-write
    bit set).
  - Given `8r000` and either mask, when checked, then `true`.

- `select-credential-source` (never throws — always returns either
  `Credentials` or `ErrorData`, same as `parse-login-response`/
  `parse-secret-response`; it's the calling *action*,
  `resolve-credentials!`, that turns `ErrorData` into a thrown `ex-info`)
  - Given both env vars set (non-blank), when selected, then returns
    `Credentials` with `:source :env`, and file `inputs` are ignored even if
    they describe an insecure file.
  - Given only `env.client-id` set, when selected, then returns `ErrorData`
    with `:type :clj-infisical/ambiguous-credentials`.
  - Given only `env.client-secret` set, then same as above (symmetry).
  - Given no env vars, dir root-owned/write-protected and both files
    present/secure (owned by process user, `0600`), when selected, then
    returns `Credentials` with `:source :file` and trimmed values.
  - Given no env vars, dir owned by root but group-writable (`0775`), when
    selected, then `:clj-infisical/insecure-credential-files` naming the
    directory.
  - Given no env vars, dir owned by a non-root user (even if not
    group/other-writable), when selected, then
    `:clj-infisical/insecure-credential-files` naming the directory.
  - Given no env vars, dir readable/listable by others but not writable
    (`0755`, root-owned), when selected (and files are otherwise secure),
    then this does **not** by itself produce an error — proves directory
    read/execute bits for group/other are permitted, only write bits and
    ownership are checked on the directory.
  - Given no env vars, dir secure but `client_id` file world-readable
    (`0644`), when selected, then `:clj-infisical/insecure-credential-files`
    naming that file.
  - Given no env vars, dir secure, both files mode-secure but one owned by
    root instead of the process user, when selected, then
    `:clj-infisical/insecure-credential-files` naming that file (ownership
    direction is the opposite of the directory's — flagged explicitly since
    it's easy to get backwards).
  - Given no env vars, dir secure, both files secure but one is a symlink,
    when selected, then `:clj-infisical/insecure-credential-files`.
  - Given no env vars and both files don't exist (`exists?` false on both),
    when selected, then `:clj-infisical/credentials-not-found` — checked
    *before* any security predicate, so this holds even if the fixture also
    sets e.g. `dir-owned-by-root?` to `false`.
  - Given no env vars, dir doesn't exist at all (`dir-exists?` false, and
    consequently both files `exists?` false), when selected, then
    `:clj-infisical/credentials-not-found`, not an insecure-file error —
    absence isn't insecurity.
  - Given no env vars, `client-id-file.exists?` true but
    `client-secret-file.exists?` false (partial setup — only one file was
    ever created), when selected, then `:clj-infisical/insecure-credential-files`,
    **not** `credentials-not-found` — a partial setup is a misconfiguration
    to flag, not a clean "nothing configured" state.
  - Given file contents with a trailing newline, when selected, then the
    returned `Credentials` values have no trailing whitespace.

- `read-env`
  - Given `{"INFISICAL_CLIENT_ID" "cid" "INFISICAL_CLIENT_SECRET" "csecret"}`,
    when called, then returns `{:client-id "cid" :client-secret "csecret"}`.
  - Given `{}` (neither key present), when called, then returns
    `{:client-id nil :client-secret nil}`.
  - Given a `java.util.Map` (what `System/getenv` actually returns, not a
    Clojure map) with both keys present, when called, then still works —
    proves `read-env` doesn't assume a Clojure-native map.

### 8.2 `clj-infisical.credentials` — actions

- `read-env!` — not independently unit-tested: it's a one-line wrapper,
  `(read-env (System/getenv))`, and `System/getenv` can't be faked from
  within the same JVM process without reflection hacks not worth the
  complexity here. Its behavior is covered by `read-env`'s tests above (the
  logic) plus `resolve-credentials!`'s tests below (the integration point,
  via rebinding `read-env!` itself, which — being public — needs no such
  hack).

- `stat-credential-files!`
  - Given a real temp dir created with mode `0755` containing `client_id`
    (mode `0600`, content `"abc\n"`) and `client_secret` (mode `0600`,
    content `"xyz\n"`), when called, then the returned map's
    `dir-not-group-or-other-writable?`/`no-group-or-other-bits?`/`exists?`/
    `symlink?` fields are all as expected and `content` includes the
    trailing newline (trimming is the calculation's job, not this action's).
  - Given the directory doesn't exist, when called, then `exists?` is
    `false` for the dir and both files, with no exception thrown (absence
    is data, not an I/O error).
  - Given `client_id` is actually a symlink to another file, when called,
    then `symlink?` is `true` regardless of the symlink target's own
    permissions.
  - **Caveat, not a test to write as-is:** a real temp dir created by the
    test process is owned by the test-runner's user, not `root`, so
    `dir-owned-by-root?` will be `false` for every dir the test suite can
    actually create — the "dir IS root-owned" branch can only be exercised
    running as root (impractical/undesirable in CI). That branch is instead
    covered at the `select-credential-source` calculation level (§8.1)
    using a synthetic `inputs` map. This action's own test only needs to
    confirm `dir-owned-by-root?` correctly reports `false` for a
    known-non-root-owned dir, i.e. that the interop call works at all.

- `resolve-credentials!` (`read-env!` and `stat-credential-files!` both
  rebound via `with-redefs` in every scenario below — real env vars/files
  are never touched in unit tests)
  - Given `read-env!` rebound to return both client id/secret, when called,
    then returns `Credentials` **without** touching the filesystem
    (`stat-credential-files!` rebound to throw if invoked, to prove the
    short-circuit in §5.2).
  - Given `read-env!` rebound to return `{:client-id nil :client-secret nil}`
    and `stat-credential-files!` rebound to return a fixture that
    `select-credential-source` (real, unmocked) would reject, when called,
    then throws `ex-info` with the matching `:type` from `ex-data`.

### 8.3 `clj-infisical.auth` — calculations

- `login-request`
  - Given a site-url, client-id, client-secret, when built, then the URL is
    `{site-url}/api/v1/auth/universal-auth/login` and `json-body-map`
    contains exactly `{"clientId" ... "clientSecret" ...}`.

- `parse-login-response`
  - Given `{:status 200 :body "{\"accessToken\":\"t\",\"expiresIn\":7200,\"tokenType\":\"Bearer\"}"}`,
    when parsed, then returns `AccessToken` with those three fields.
  - Given `{:status 401 :body "{\"message\":\"bad creds\"}"}`, when parsed,
    then `:clj-infisical/auth-failed` `ErrorData` with `:status 401`,
    `:body` the raw string, and `:parsed {"message" "bad creds"}` — proving
    the real Infisical error message survives unmangled.
  - Given `{:status 200 :body "not json"}`, when parsed, then
    `:clj-infisical/invalid-response`, not an uncaught JSON parse
    exception.
  - Given `{:status 500 :body "<html>Bad Gateway</html>"}` (a non-JSON error
    body, e.g. from a proxy in front of Infisical), when parsed, then
    `:clj-infisical/auth-failed` `ErrorData` with `:parsed nil` and `:body`
    holding the raw HTML — proves a non-JSON error body degrades gracefully
    instead of throwing from inside the parser.

### 8.4 `clj-infisical.auth` — actions

- `login!`
  - Given `post-json!` rebound to return a canned `200` response, when
    called, then returns the `AccessToken` `parse-login-response` would
    produce from that body (i.e. it delegates, doesn't reimplement).
  - Given `post-json!` rebound to return a `401`, when called, then throws
    `ex-info` of type `:clj-infisical/auth-failed`.

### 8.5 `clj-infisical.secrets` — calculations

- `secret-request`
  - Given a `Config` and `AccessToken` and secret name, when built, then
    `url` is `{site-url}/api/v3/secrets/raw/{secret-name}`, `query-params`
    is exactly `{"workspaceId" ... "environment" ... "secretPath" ...}`
    (no `viewSecretValue`, per §3.2), and `headers` has
    `Authorization: Bearer {token}`.

- `parse-secret-response`
  - Given a `200` body with only `secret.secretValue`, when parsed, then
    returns `Secret` `{:secret-value "..."}`.
  - Given a `200` body with `secret.secretValue` plus other keys
    (`secretKey`, `version`, ...), when parsed, then returns `Secret` with
    **all** of those keys keywordized and present (`:secret-key`,
    `:version`, ...) — proves the "raw" passthrough actually passes
    everything through rather than narrowing to just the value.
  - Given `404`, when parsed, then `:clj-infisical/secret-not-found`
    `ErrorData` with `:parsed` populated from the body when it's JSON.
  - Given `500` with a JSON body `{"message":"internal error"}`, when
    parsed, then `:clj-infisical/http-error` `ErrorData` with
    `:parsed {"message" "internal error"}`.
  - Given `200` with a body missing the `secret` key, when parsed, then
    `:clj-infisical/invalid-response`.
  - Given `200` with a `secret` object missing `secretValue`, when parsed,
    then `:clj-infisical/invalid-response`.

### 8.6 `clj-infisical.secrets` — actions

- `fetch-secret!`
  - Given `get-json!` rebound to return a canned `200`, when called, then
    returns the `Secret` `parse-secret-response` would produce (all keys,
    not just `:secret-value`).
  - Given `get-json!` rebound to return a `404`, when called, then throws
    `:clj-infisical/secret-not-found`.

### 8.7 `clj-infisical.core` — `get-secret!` / `get-secret-raw!` (action, integration-style with rebinding)

These two share `-fetch-secret!` (§5.6), so most scenarios are phrased
against both; only the final return-shape scenario differs between them.

- Given `workspace-id` missing from the argument map, when either is
  called, then throws `:clj-infisical/invalid-arguments` **without**
  calling `resolve-credentials!`, `login!`, or `fetch-secret!` (proves the
  guard runs first — rebind all three to throw if invoked).
- Given `client-id`/`client-secret` supplied explicitly in the argument map,
  when either is called, then `resolve-credentials!` is never invoked
  (rebind it to throw if called) and the explicit values flow through to
  `login!`.
- Given no explicit credentials, `resolve-credentials!` rebound to return a
  fixed `Credentials`, `login!` rebound to return a fixed `AccessToken`, and
  `fetch-secret!` rebound to return a fixed multi-key `Secret`
  (`{:secret-value "s" :secret-key "k" :version 3}`), when `get-secret-raw!`
  is called, then it returns that `Secret` map unchanged, all three keys
  intact.
- Same setup, when `get-secret!` is called instead, then it returns just
  `"s"` — a plain string, not a map.
- Given `resolve-credentials!` (rebound) throws
  `:clj-infisical/credentials-not-found`, when either is called, then that
  exception propagates unchanged (no wrapping/swallowing).
- Given `login!` (rebound) throws `:clj-infisical/auth-failed`, when either
  is called, then that exception propagates unchanged and `fetch-secret!` is
  never invoked.
- Given `environment`/`secret-path`/`site-url` omitted, when either is
  called, then the `Config`/URL built and passed downstream use the
  documented defaults (`"dev"`, `"/"`, `"https://app.infisical.com"`).

## 9. Decisions log

Everything raised in earlier drafts is now resolved; nothing is blocking
test-writing. Kept here as a record of *why*, not as open items:

- **Endpoint shapes** (§3) — confirmed against a real working curl script
  against a self-hosted instance, not just docs.
- **Credential file names** (§5.2) — `/etc/infisical/client_id` and
  `/etc/infisical/client_secret` confirmed as-is.
- **Retry strategy** (§2) — none in this library; `clj-http-lite` has no
  built-in retry-on-failure (only connect/socket timeouts), so a single
  attempt per call, connection failures propagate uncaught.
- **`get-secret!` return shape** — reversed from the prior draft. That draft
  dropped a second "raw" function on the reasoning that the confirmed
  response only guaranteed `secret.secretValue`, so there was nothing else
  to expose. That reasoning had it backwards: not-yet-confirmed fields are
  exactly why "raw" access matters — the original curl script piped the
  full response through `jq` rather than assuming its shape, and this
  library shouldn't assume more than that script did. §5.6 now exposes both
  `get-secret!` (string) and `get-secret-raw!` (the full `Secret` map, every
  field Infisical returns, passed through), sharing one private
  orchestration action. The same "don't discard what we can't yet confirm"
  reasoning applies to errors: `ErrorData` (§5.1/§7) now carries the
  JSON-decoded response body (`:parsed`) alongside the raw one, so a 400/500
  response's real `message`/`error` fields are directly available instead
  of forcing every caller to re-parse `:body` to get at Infisical's actual
  error text.
- **Directory permission rule** (§5.2 rule 3) — root-owned `/etc/infisical`,
  no group/other *write* bits, group/other read+execute permitted. Modeled
  explicitly on OpenSSH's own permission check on `~/.ssh`, which rejects
  group/other-writable directories/keys but doesn't require the directory
  itself to be unreadable by others.
- **No standard convention to defer to** — checked whether the official
  `infisical` CLI or `infisical agent` has a fixed filesystem convention for
  universal-auth credentials (e.g. always `/etc/infisical/...`). It doesn't:
  the CLI proper only reads `INFISICAL_TOKEN` from the environment or
  `--client-id`/`--client-secret` flags, with no file-based lookup at all.
  The Agent supports reading credentials from files, but the paths are
  arbitrary and set by the operator in its YAML config (example in the docs
  uses relative paths like `./client-id`) — there's no default location. So
  there's no existing standard this library could align to or diverge from;
  `/etc/infisical/client_id` + `/etc/infisical/client_secret` stand as
  originally specified.

`test/clj_infisical/**` should now be written straight from §8, before any
`src/clj_infisical/**` implementation exists.

## 10. Packaging (Clojars)

This library is intended to be published to Clojars for consumption by other
projects, so a few things belong in scope even though they don't affect
`src/`/`test/` content:

- `project.clj` needs real values in place of the `FIXME`s currently there:
  `:description`, `:url` (repo URL), and a `:license` — EPL-2.0 (the current
  `lein new` default) is Clojars-compatible and requires no change unless
  there's a preference otherwise.
- Group/artifact id: default Leiningen naming gives artifact id
  `clj-infisical` with group id equal to the artifact id (`clj-infisical`),
  which is only allowed on Clojars if this account already owns that group,
  or it's claimed via Clojars' verified-group process. Otherwise this needs
  a `groupId/artifactId` pair like `org.clojars.<username>/clj-infisical`.
  This affects `project.clj`'s `defproject` line and is worth deciding
  before the first deploy, though it doesn't block writing `src`/`test`.
- Versioning: start at `0.1.0` (drop `-SNAPSHOT` only for the actual
  release-to-Clojars build); SNAPSHOT versions are not resolvable by
  consumers off Clojars' release repo.
- `README.org` currently has `FIXME` usage/license placeholders — needs real
  usage examples (mirroring `get-secret!`'s signature from §5.6) before
  first release, since it's what Clojars/cljdoc display.
- Clojars deploy credentials (token) are a local/CI environment concern, not
  something this spec or the library's code should reference or embed.

None of this blocks writing tests from §8 or implementing against them —
it's a pre-`lein deploy` checklist, not a design constraint on the code
itself.
