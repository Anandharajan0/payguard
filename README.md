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
- Budget enforcement is fail-closed by default. The configured daily budget is
  not implemented until atomic durable reservation exists.
- For a local hackathon demonstration only, set
  `PAYGUARD_BUDGET_MODE=demo-unenforced`. Responses and policy traces
  explicitly disclose that the daily budget is not enforced in this mode.
- PayPal timeouts and non-terminal provider outcomes are recorded as requiring
  reconciliation. Durable recovery and provider reconciliation belong to the
  PostgreSQL/workflow phase.
- The PayPal Sandbox adapter remains the execution integration, using the
  Sandbox OAuth flow and stable `PayPal-Request-Id` values.
