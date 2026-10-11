create table resolution_cases (
    case_id uuid primary key,
    mandate_id text not null references merchant_mandates(mandate_id),
    idempotency_key varchar(128) not null,
    request_fingerprint char(64) not null,
    customer_issue text not null,
    evidence_snapshot jsonb not null,
    model_proposal jsonb,
    status text not null check (status in (
        'PROCESSING', 'PROPOSAL_READY', 'INFORMATION_REQUIRED',
        'PROPOSAL_REJECTED', 'REFUND_SUBMITTED', 'MODEL_FAILED'
    )),
    operation_id uuid unique references refund_operations(operation_id),
    processing_claim_token uuid,
    processing_lease_expires_at timestamptz,
    failure_code text,
    created_at timestamptz not null,
    updated_at timestamptz not null,
    check ((processing_claim_token is null)
            = (processing_lease_expires_at is null)),
    check (status <> 'PROCESSING' or processing_claim_token is not null),
    unique (mandate_id, idempotency_key)
);

create index resolution_cases_operation_idx
    on resolution_cases (operation_id)
    where operation_id is not null;
