-- hot_score: time-decayed popularity score used for ordering deal feeds.
-- Added here because the server code references it in ORDER BY clauses.
-- Formula (applied via the trigger below): upvotes / (age_hours + 2)^1.5
ALTER TABLE deals
    ADD COLUMN IF NOT EXISTS hot_score DOUBLE PRECISION NOT NULL DEFAULT 0.0;

-- Back-fill existing rows so they are immediately sortable by hot_score.
-- Uses quality_score as a proxy until the real score is computed by the worker.
UPDATE deals SET hot_score = quality_score WHERE hot_score = 0;

-- Add in-store / online tagging to deals table.
-- is_instore: true when the deal requires physical store visit or in-store pickup
-- is_online:  true when the deal is available online (default for all existing deals)
ALTER TABLE deals
    ADD COLUMN IF NOT EXISTS is_instore BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_online  BOOLEAN NOT NULL DEFAULT true;

-- Same columns for coupons — service-business coupons (Great Clips, Jiffy Lube, etc.)
-- are in-store only; online promo codes default to is_online=true, is_instore=false.
ALTER TABLE coupons
    ADD COLUMN IF NOT EXISTS is_instore BOOLEAN NOT NULL DEFAULT false,
    ADD COLUMN IF NOT EXISTS is_online  BOOLEAN NOT NULL DEFAULT true;

-- Index for approved in-store deals
CREATE INDEX IF NOT EXISTS idx_deals_instore ON deals (is_instore, status)
    WHERE status = 'approved';

-- Index for approved in-store coupons
CREATE INDEX IF NOT EXISTS idx_coupons_instore ON coupons (is_instore, status)
    WHERE status = 'approved';
