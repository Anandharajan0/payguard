create table paypal_capture_provenance (
    capture_id text primary key,
    mandate_id text not null references merchant_mandates(mandate_id),
    amount_cents bigint not null check (amount_cents > 0),
    currency char(3) not null,
    order_id text not null,
    registered_at timestamptz not null,
    no_external_refunds_confirmed boolean not null
);
