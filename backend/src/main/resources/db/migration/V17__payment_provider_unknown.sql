-- TSF's payment-status/event contract does not expose the underlying provider.
-- Preserve unknown as NULL rather than inventing XENDIT/OPN. Existing provider CHECK remains.
ALTER TABLE payment_status_snapshot ALTER COLUMN provider DROP NOT NULL;
