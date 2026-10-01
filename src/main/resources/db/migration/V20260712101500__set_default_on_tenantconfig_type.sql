-- Setzt einen expliziten DEFAULT auf tenantconfig.type. Die vorherige
-- Migration V20251003180600__extend_type_in_tenantconfig_table.sql
-- verwendete ADD COLUMN IF NOT EXISTS mit DEFAULT — der DEFAULT-Teil
-- greift dabei nur beim ADD, nicht bei bereits existierender Column.
-- Effekt: Bootstrap-Wizard und andere INSERT-Pfade ohne expliziten
-- type-Wert scheitern mit "column type violates NOT NULL".
ALTER TABLE "eda"."tenantconfig" ALTER COLUMN "type" SET DEFAULT 'MAIL';
