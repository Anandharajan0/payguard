insert into merchant_mandates (
    mandate_id, status, daily_limit_cents, created_at, updated_at
) values (
    'payguard-demo-mandate', 'ACTIVE', 200000, now(), now()
)
on conflict (mandate_id) do nothing;
