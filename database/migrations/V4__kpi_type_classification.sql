-- V4__kpi_type_classification.sql
--
-- Introduces the KPI classification that separates manager-assessed KPIs from
-- HR-assessed KPIs.
--
-- Safety rules for existing data:
--   * Every existing KPI and every existing frozen KPI snapshot is classified
--     as 'MANAGER' and therefore keeps exactly the previous behaviour:
--     75% manager rating + 25% HR rating, both still required.
--   * No row is deleted, renamed or reclassified into a different scoring rule.
--   * Published and finalized results are not touched at all.
--
-- performance_result_items must accept a NULL manager_rating, because an
-- HR-assessed KPI has no manager rating at all. A row still has to carry at
-- least one rating, which is enforced by the new check constraint.

ALTER TABLE kpi_master
    ADD COLUMN kpi_type VARCHAR(20) NOT NULL DEFAULT 'MANAGER';

ALTER TABLE kpi_master
    ADD CONSTRAINT ck_kpi_type
        CHECK (kpi_type IN ('MANAGER', 'HR'));

ALTER TABLE employee_kpis
    ADD COLUMN kpi_type VARCHAR(20) NOT NULL DEFAULT 'MANAGER';

ALTER TABLE employee_kpis
    ADD CONSTRAINT ck_employee_kpi_type
        CHECK (kpi_type IN ('MANAGER', 'HR'));

-- Existing rows already satisfy this: both ratings were mandatory before.
ALTER TABLE performance_result_items
    ALTER COLUMN manager_rating DROP NOT NULL;

ALTER TABLE performance_result_items
    ADD CONSTRAINT ck_result_item_stage_rating
        CHECK (manager_rating IS NOT NULL OR hr_rating IS NOT NULL);

CREATE INDEX ix_kpi_master_type ON kpi_master (kpi_type);
