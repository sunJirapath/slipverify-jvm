# Transactional payment acceptance

`VALID` means the provider confirmed the submitted transfer and the requested checks passed. It does not mean
your order was credited. Set the expected amount, receiver and maximum age from server-side order data.

The duplicate store marks the slip **before** `verify()` returns. That mark has a TTL and is separate from the
application database transaction. A crash after verification can leave a marked slip with an unpaid order;
an expired marker or a restarted in-memory store does not prove the transfer was never used.

## Durable identity and atomic credit

Use `(slip_type, sending_bank, trans_ref)` as a unique transfer identity, using the fields returned by a `VALID`
result. Keep this record for the application's payment-retention period, independently of the duplicate TTL.
Keep the merchant or tenant scope consistent with the duplicate store; a transfer must not credit two orders.

Example PostgreSQL schema (adapt the order table and amount type to your application):

```sql
CREATE TABLE accepted_transfers (
    slip_type text NOT NULL,
    sending_bank text NOT NULL,
    trans_ref text NOT NULL,
    order_id bigint NOT NULL REFERENCES orders(id),
    amount numeric(18, 2) NOT NULL CHECK (amount > 0),
    PRIMARY KEY (slip_type, sending_bank, trans_ref),
    UNIQUE (order_id)
);
```

Verify outside the database transaction so the provider network call does not hold an order lock. Only after
`VALID`, start a transaction, lock the order, and recheck that it is unpaid and that its amount and receiving
account still agree with the checks used for verification. Then insert the transfer and credit the order in the
**same transaction**. For example, with an existing `orders(id, amount, status)` table:

```sql
BEGIN;
SELECT amount, status FROM orders WHERE id = :order_id FOR UPDATE;
-- Application: require status = 'UNPAID', and revalidate the server-side payment expectations.
INSERT INTO accepted_transfers (slip_type, sending_bank, trans_ref, order_id, amount)
VALUES (:slip_type, :sending_bank, :trans_ref, :order_id, :confirmed_amount);
UPDATE orders SET status = 'PAID' WHERE id = :order_id AND status = 'UNPAID';
-- Application: require exactly one updated row; otherwise roll back.
COMMIT;
```

A unique-key conflict must roll back crediting. Read the existing record separately: a transfer already credited
to this order is an idempotent success; a transfer credited to a different order is a rejection. Never credit
directly from a `DUPLICATE` result or from client-supplied amount/account fields.

## Retrying after failure

If your database commit failed after the short-lived mark, a duplicate result alone cannot distinguish a
successful previous credit from an interrupted attempt. Read your accepted-transfer record first. If there is
no record, leave the order unpaid and reconcile by obtaining a fresh provider confirmation with the same
server-side checks. For database-backed payment acceptance, you can omit the transient `DuplicateStore` and use
the database's unique constraint as the authoritative replay barrier; concurrent verification calls can then
cost provider quota, but only one transaction can credit the transfer. `KnownSlips` can avoid most repeat calls
by consulting these durable records before verification, but it is a pre-check, not a concurrency lock.

If you keep the transient store, do not delete marks indiscriminately to enable retries. Use an application-owned
reconciliation path. Provider-side duplicate flags may need separate reconciliation too; do not turn them into
proof of a new payment.
