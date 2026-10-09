# PAYGUARD

PAYGUARD is a prototype refund firewall for the PayPal AI Hackathon. It
separates AI-proposed refund intent from deterministic policy authorization and
PayPal execution.

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
  prototype. They are not durable across restart and do not support
  multi-instance deployment.
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
