create table merchant_mandates (
    mandate_id text primary key,
    status text not null,
    daily_limit_cents bigint not null check (daily_limit_cents > 0),
    created_at timestamptz not null,
    updated_at timestamptz not null
);

create table budget_days (
    mandate_id text not null references merchant_mandates(mandate_id),
    budget_date date not null,
    daily_limit_cents bigint not null check (daily_limit_cents > 0),
    reserved_cents bigint not null default 0 check (reserved_cents >= 0),
    committed_cents bigint not null default 0 check (committed_cents >= 0),
    primary key (mandate_id, budget_date),
    check (reserved_cents + committed_cents <= daily_limit_cents)
);

create table refund_operations (
    operation_id uuid primary key,
    mandate_id text not null references merchant_mandates(mandate_id),
    idempotency_key varchar(128) not null,
    request_fingerprint char(64) not null,
    capture_id text not null,
    amount_cents bigint not null check (amount_cents > 0),
    currency char(3) not null,
    transaction_created_at timestamptz not null,
    requester_agent_id text not null,
    decision_state text not null,
    decision_reason text not null,
    budget_status text not null,
    budget_explanation text not null,
    state text not null,
    approval_id uuid unique,
    correlation_id uuid not null,
    provider_request_id text not null unique,
    provider_refund_id text,
    provider_status text,
    error_code text,
    lease_expires_at timestamptz,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    completed_at timestamptz,
    unique (mandate_id, idempotency_key),
    check (state in (
        'APPROVED', 'PENDING_APPROVAL', 'DENIED', 'IN_FLIGHT',
        'EXECUTED', 'FAILED', 'RECONCILIATION_REQUIRED'
    ))
);

create table budget_reservations (
    operation_id uuid primary key references refund_operations(operation_id),
    mandate_id text not null,
    budget_date date not null,
    amount_cents bigint not null check (amount_cents > 0),
    status text not null check (status in ('RESERVED', 'RELEASED', 'SETTLED')),
    created_at timestamptz not null,
    updated_at timestamptz not null,
    released_at timestamptz,
    settled_at timestamptz,
    foreign key (mandate_id, budget_date)
        references budget_days(mandate_id, budget_date)
);

create table audit_events (
    event_id bigserial primary key,
    operation_id uuid references refund_operations(operation_id),
    correlation_id uuid not null,
    state text not null,
    explanation text not null,
    actor_id text,
    actor_role text,
    mandate_id text,
    approval_id uuid,
    capture_id text,
    amount_cents bigint,
    currency char(3),
    paypal_refund_id text,
    paypal_status text,
    error_code text,
    occurred_at timestamptz not null
);

create index refund_operations_stale_in_flight_idx
    on refund_operations (lease_expires_at)
    where state = 'IN_FLIGHT';
create index refund_operations_approval_idx
    on refund_operations (approval_id)
    where approval_id is not null;
create index audit_events_operation_idx on audit_events (operation_id, event_id);
