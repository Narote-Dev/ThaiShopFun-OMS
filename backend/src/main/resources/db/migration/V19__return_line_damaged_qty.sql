-- T20: per-piece return receive tracks resellable vs damaged counts on each return line.

ALTER TABLE return_line DROP CONSTRAINT return_line_condition_check;

ALTER TABLE return_line
  ADD COLUMN damaged_qty integer NOT NULL DEFAULT 0;

ALTER TABLE return_line ADD CONSTRAINT return_line_damaged_qty_check CHECK (damaged_qty BETWEEN 0 AND qty);

ALTER TABLE return_line ADD CONSTRAINT return_line_received_total_check CHECK (
  restocked_qty + damaged_qty <= qty
);

ALTER TABLE return_line ADD CONSTRAINT return_line_condition_check CHECK (
  condition IS NULL OR condition IN ('RESELLABLE', 'DAMAGED', 'MIXED')
);

COMMENT ON COLUMN return_line.damaged_qty IS
  'Units received as damaged; restocked_qty holds RESELLABLE units. Sum equals qty when receive is complete.';
