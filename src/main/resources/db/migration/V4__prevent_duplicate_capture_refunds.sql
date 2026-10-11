lock table refund_operations in share row exclusive mode;

do $capture_guard_preflight$
declare
    conflicts text;
begin
    select string_agg(
        format('capture_id=%s operations=[%s]', capture_id, operations),
        '; ' order by capture_id
    )
    into conflicts
    from (
        select
            capture_id,
            string_agg(
                format('%s (%s)', operation_id, state),
                ', ' order by created_at, operation_id
            ) as operations
        from refund_operations
        where state in (
            'PENDING_APPROVAL',
            'APPROVED',
            'IN_FLIGHT',
            'EXECUTED',
            'RECONCILIATION_REQUIRED'
        )
        group by capture_id
        having count(*) > 1
    ) duplicate_captures;

    if conflicts is not null then
        raise exception using
            message = format(
                'Cannot create refund_operations_one_active_per_capture: legacy blocking refund operations share a capture. Conflicts: %s. Reconcile each operation against PayPal and review its approval, budget reservation, and audit history; preserve all financial records, resolve the duplicate risk through an operator-approved action, then retry the migration.',
                conflicts
            ),
            detail = conflicts,
            hint = 'Do not delete, merge, cancel, or reclassify financial operations automatically; do not release reservations as migration repair.';
    end if;
end
$capture_guard_preflight$;

create unique index refund_operations_one_active_per_capture
    on refund_operations (capture_id)
    where state in (
        'PENDING_APPROVAL',
        'APPROVED',
        'IN_FLIGHT',
        'EXECUTED',
        'RECONCILIATION_REQUIRED'
    );
