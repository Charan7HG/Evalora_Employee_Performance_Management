-- V3__case_insensitive_employee_code_uniqueness.sql
--
-- V1 declares uq_employees_code UNIQUE (employee_code), which PostgreSQL treats
-- as case-sensitive. The application, however, treats employee codes as
-- case-insensitive (existsByEmployeeCodeIgnoreCase / findByEmployeeCodeIgnoreCase),
-- so concurrent requests could otherwise insert both "EMP001" and "emp001".
--
-- The functional unique index below closes that gap at the database level and
-- supplements the case-sensitive V1 constraint; it does not replace it.
--
-- Existing rows are never renamed or deleted. If two rows already share a code
-- that differs only by case, the guard aborts the migration with the offending
-- values. Flyway runs PostgreSQL migrations in a transaction, so the failed
-- migration leaves both the data and the schema unchanged. An operator must
-- resolve the conflict manually and then rerun the migration.

DO $$
DECLARE
    duplicate_details text;
BEGIN
    SELECT string_agg(
               normalized_code || ' (' || duplicate_count || ' rows)',
               '; ' ORDER BY normalized_code)
      INTO duplicate_details
      FROM (
            -- duplicate-check-start
            SELECT lower(employee_code) AS normalized_code,
                   count(*) AS duplicate_count
              FROM employees
             GROUP BY lower(employee_code)
            HAVING count(*) > 1
            -- duplicate-check-end
           ) duplicate_groups;

    IF duplicate_details IS NOT NULL THEN
        RAISE EXCEPTION
            'Cannot create unique index ux_employees_code_lower because employee codes that differ only by letter case already exist: %',
            duplicate_details
            USING HINT = 'Rename or remove the conflicting employee codes, then rerun the migration. No data was changed.';
    END IF;
END
$$;

CREATE UNIQUE INDEX ux_employees_code_lower
    ON employees (LOWER(employee_code));
