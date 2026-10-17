# PAYGUARD

PAYGUARD is a prototype refund firewall for the PayPal AI Hackathon. It
separates AI-proposed refund intent from deterministic policy authorization and
PayPal execution.

## Durable Financial Spine

Set `PAYGUARD_PERSISTENCE_MODE=postgres` (the default) and configure
`PAYGUARD_DB_URL`, `PAYGUARD_DB_USERNAME`, and `PAYGUARD_DB_PASSWORD`.
The configured `PAYGUARD_MANDATE_ID` is provisioned if absent with
`PAYGUARD_DAILY_LIMIT_CENTS` (default `200000`); existing mandate status and
limits are not overwritten.
Flyway applies the durable schema on startup. The application uses explicit
Spring JDBC transactions and UTC budget days; it does not use JPA.

For local unit/security tests, the test configuration selects
`payguard.persistence.mode=in-memory`. Real PostgreSQL locking tests run when
`PAYGUARD_TEST_POSTGRES_URL` is set, for example
`jdbc:postgresql://localhost:55432/payguard`; they are intentionally skipped
when no PostgreSQL test database is supplied rather than replaced by H2.

## Prototype security and durability boundaries

- The current HTTP API is **machine-to-machine only** and uses separate
  environment-backed HTTP Basic credentials for `AGENT` and `APPROVER` roles.
- Configure `PAYGUARD_AGENT_USERNAME`, `PAYGUARD_AGENT_PASSWORD`,
  `PAYGUARD_APPROVER_USERNAME`, `PAYGUARD_APPROVER_PASSWORD`, and
  `PAYGUARD_APPROVER_MANDATE_ID` before starting the application.
- Use HTTPS/TLS outside local development. Never embed these credentials in
  browser JavaScript or use them directly from an operator dashboard.
- `PAYGUARD_SECURITY_CLIENT_MODE` must remain `machine-to-machine` in this
  slice. Browser dashboard authentication requires a separate session/OIDC and
  CSRF design in a later phase.
- Idempotency keys, approvals, and audit events are process-local in this
  prototype mode only. PostgreSQL mode persists operations, approvals,
  idempotency, budget reservations, and audit events across restart.
- PostgreSQL mode uses UTC budget days and row-locked atomic reservations.
  A reservation is settled only for a known successful PayPal outcome,
  released for a definite failure, and retained for ambiguous outcomes.
- Startup recovery marks expired `IN_FLIGHT` operations as requiring
  reconciliation without releasing their reservations. It cannot determine
  whether PayPal moved money; provider lookup or webhooks remain future work.
- Budget enforcement is fail-closed by default and uses atomic durable
  reservations in PostgreSQL mode. It is intentionally disabled only in the
  explicitly selected local demo mode.
- For a local hackathon demonstration only, set
  `PAYGUARD_BUDGET_MODE=demo-unenforced`. Responses and policy traces
  explicitly disclose that the daily budget is not enforced in this mode.
- PayPal timeouts and non-terminal provider outcomes are recorded as requiring
  reconciliation. Durable recovery and provider reconciliation belong to the
  PostgreSQL/workflow phase.
- The PayPal Sandbox adapter remains the execution integration, using the
  Sandbox OAuth flow and stable `PayPal-Request-Id` values.

## Payment resolution boundary

The prototype also exposes `POST /resolution-cases` for a bounded support
resolution flow. The fixture resolution agent classifies a customer issue
against trusted transaction evidence and may propose a refund or request more
information. It cannot invent a capture ID, amount, currency, mandate, or
authority. The existing deterministic policy, budget reservation, approval
workflow, audit path, and PayPal adapter remain the only authorization and
execution path.

The default evidence mode is the deterministic fixture provider for
`CAPTURE-35`, `CAPTURE-475`, and `CAPTURE-600`:

```text
PAYGUARD_RESOLUTION_EVIDENCE_MODE=fixture
```

Fixture evidence is useful for offline tests only and is never live PayPal
verification. Set `PAYGUARD_RESOLUTION_EVIDENCE_MODE=paypal` to require the
PayPal capture-evidence provider. In that mode a capture must first be
registered in the durable `paypal_capture_provenance` table and belong to the
configured mandate. The provider then performs
`GET /v2/payments/captures/{capture_id}` and accepts only a strict response
whose ID, `COMPLETED` status, amount, currency, and timestamp match the
registered record. This endpoint does not establish complete refund history or
remaining refundable balance, so live evidence is deliberately marked
`UNKNOWN` for refund eligibility and cannot create an executable refund
operation. PayPal outages, malformed responses, mismatches, and unregistered
captures return no evidence; live mode never falls back to the fixture
provider.

The isolated Sandbox setup utility creates and captures an Order after buyer
approval, prints the real capture ID, and can register its immutable
provenance:

```bash
export PAYPAL_CLIENT_ID=...
export PAYPAL_CLIENT_SECRET=...
export PAYGUARD_DB_URL=jdbc:postgresql://localhost:5432/payguard
export PAYGUARD_DB_USERNAME=payguard
export PAYGUARD_DB_PASSWORD=...
bash scripts/paypal-sandbox-setup.sh 35.00
```

The script uses only the canonical Sandbox origin, keeps credentials in
short-lived mode-0600 curl configuration files rather than command-line
arguments, and does not log tokens or secrets. After the buyer approval step,
set `PAYGUARD_REGISTER_CAPTURE=true` to insert the returned capture's
provenance into PostgreSQL. Registration is only provenance; it does not
authorize a refund or prove that PayPal exposes a complete external-refund
ledger. The application still does not claim to discover refunds made outside
PAYGUARD.

For a live Sandbox run, set:

```text
PAYGUARD_RESOLUTION_EVIDENCE_MODE=paypal
PAYPAL_BASE_URL=https://api-m.sandbox.paypal.com
PAYGUARD_PERSISTENCE_MODE=postgres
PAYGUARD_BUDGET_MODE=fail-closed
```

Run the three scenarios with real registered capture IDs:

1. A newly captured $35 payment produces a live capture snapshot, but remains
   blocked because remaining refundable balance is not provider-verified.
2. A newly captured $475 payment has the same conservative live-evidence
   block; the approval workflow cannot bypass unknown refund eligibility.
3. A newly captured $600 payment is also blocked before any refund request is
   sent. The automatic $35 and approval-gated $475 demonstrations require
   fixture mode until authoritative PayPal refund-balance evidence exists.

The response and PostgreSQL records expose the real provider capture ID,
immutable evidence snapshot, model proposal, policy trace, operation ID,
PayPal idempotency key, audit record, and final operation outcome. Known
refund IDs can be looked up with PayPal's refund-details endpoint for future
reconciliation workflows, but webhook ingestion, signature verification,
event deduplication, and safe ambiguous-outcome reconciliation are not part of
this milestone. External refunds therefore remain an explicit limitation, and
ambiguous operations remain reserved and capture-blocking.

Resolution cases now use stable trusted evidence snapshots and durable
resolution idempotency in PostgreSQL. Evidence includes the original capture
amount, remaining refundable amount, capture status, prior refunds, and an
evidence version; the model cannot supply or change those fields. The
validated proposal is persisted before the refund workflow. A claim token and
short database lease, protected by PostgreSQL row locks, prevent concurrent
model calls; expired `PROCESSING` claims can be reclaimed on replay. Refund
operations use a case-derived idempotency key, so replay can relink an
operation created before a process interruption without repeating PayPal
execution.

The operation record remains the financial source of truth. Resolution
responses read the linked operation's latest state, including after a later
human approval, rather than copying execution state into the case. The
resolution table status records case-processing progress and its operation
link, not a competing copy of financial state.

The default `PAYGUARD_RESOLUTION_AGENT_MODE=deterministic` is an explicit
fixture fallback. Setting it to `hosted` enables the provider-neutral,
OpenAI-compatible server-side adapter configured by
`PAYGUARD_RESOLUTION_ENDPOINT`, `PAYGUARD_RESOLUTION_API_KEY`, and
`PAYGUARD_RESOLUTION_MODEL`; connect and read limits are configured with
`PAYGUARD_RESOLUTION_CONNECT_TIMEOUT_MS` and
`PAYGUARD_RESOLUTION_READ_TIMEOUT_MS`. The model receives no PayPal
credentials or tools; strict output validation and the existing deterministic
policy remain authoritative. Provider failures are recorded as
`MODEL_FAILED`; invalid output is recorded as `PROPOSAL_REJECTED`. Neither
path invokes PayPal.

Resolution proposals support full remaining-balance refunds only: an
actionable model proposal must equal the trusted evidence's entire
`remainingRefundableCents`. Partial refunds are unsupported until the requested
amount and current refundable balance can be independently established and
validated.

The database also prevents a second PAYGUARD refund operation for a capture
while any operation is pending approval, approved, in flight, executed, or
awaiting reconciliation. A PostgreSQL partial unique index enforces this
across concurrent requests and application instances; a definite failure or
policy denial does not keep the capture blocked. This guard only knows
operations recorded by PAYGUARD. It does not discover prior refunds made
outside PAYGUARD. Transaction evidence remains fixture-backed, so live
transaction safety still requires PayPal-backed remaining-balance and refund
history lookup. Webhook-driven reconciliation is not implemented. Ambiguous
PayPal results remain `RECONCILIATION_REQUIRED`, retaining their budget
reservations until reconciliation.

Every refund-endpoint HTTP error, including 422 responses with PayPal-looking
JSON, is treated as ambiguous. Response-body fields do not establish who sent
the response or whether money moved. Such operations retain their budget
reservations and block another PAYGUARD refund until reconciliation. A failed
OAuth token request is a definite non-dispatch because the refund request has
not yet been sent.

`PAYPAL_BASE_URL` may select only the canonical PayPal Sandbox origin
(`https://api-m.sandbox.paypal.com`) or Live origin
(`https://api-m.paypal.com`), optionally with a root trailing slash. Startup
rejects other schemes, hosts, ports, paths, query strings, and fragments. The
HTTP client preserves default TLS certificate-chain and hostname validation
and explicitly disables redirects for both OAuth and refund requests. Parsed
2xx refund statuses, including `FAILED`/`CANCELLED`, are trusted only over
this constrained HTTPS client; any non-2xx status is ambiguous. JVM truststore
and network-proxy configuration remain part of the deployment trust boundary.

Provider lookup and webhook reconciliation are not implemented. Therefore,
ambiguous results can remain blocked and reserved indefinitely; operators must
reconcile directly with PayPal before applying a durable resolution to such
operations. The system intentionally does not permit a new-key retry merely
because a response looks like a PayPal validation error.

Before adding the capture index, the V4 migration checks for legacy duplicate
blocking operations. If found, it aborts without changing operation,
reservation, or audit history and reports the affected capture and operations.
An operator must reconcile each operation against PayPal and review approval,
budget-reservation, and audit history before resolving the duplicate risk and
retrying the migration; the migration never repairs or reclassifies financial
records automatically. V4 takes a PostgreSQL `SHARE ROW EXCLUSIVE` lock before
the preflight and holds it through index creation in Flyway's transaction.
Refund-operation writes pause briefly during the migration; an older writer
already in progress must finish before the preflight, while later writes wait
until the index is active.
