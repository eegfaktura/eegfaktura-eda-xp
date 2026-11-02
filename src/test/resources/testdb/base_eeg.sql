-- base.EEG as eegfaktura-backend creates it (database/migration/000001_init-setup.up.sql:2-41,
-- backend a1b5b18, 2026-10-08), the table EbMsAggregator reads (EegMasterTable). Test-only: run by
-- TestDb with plain JDBC outside Flyway, so the eda schema history stays as in production.
CREATE SCHEMA IF NOT EXISTS base;

CREATE TABLE IF NOT EXISTS base.EEG
(
    tenant               VARCHAR PRIMARY KEY,
    name                 TEXT    NOT NULL,
    description          VARCHAR(40),
    periods              JSON             DEFAULT ('[]'),
    "rcNumber"           TEXT    NOT NULL,
    area                 TEXT    NOT NULL, /* Ortsgebiet (LOCAL | REGIONAL) | BEG | GEA */
    legal                TEXT    NOT NULL DEFAULT 'verein', /* Unternehmensform ("verein" | "genossenschaft" | "geselschaft") */
    gridoperator_code    TEXT    NOT NULL,
    gridoperator_name    TEXT    NOT NULL,
    "communityId"        TEXT    NOT NULL,
    "businessNr"         TEXT,
    "allocationMode"     TEXT    NOT NULL DEFAULT 'DYNAMIC', /* "DYNAMIC" | "STATIC" */
    "settlementInterval" TEXT    NOT NULL DEFAULT 'MONTHLY', /* "MONTHLY" | "QUARTER" | "BIANNUAL" | "ANNUAL" */
    "providerBusinessNr" INTEGER,
    "taxNumber"          TEXT,
    "vatNumber"          TEXT,
    subjecttovat         BOOLEAN,
    "contactPerson"      TEXT,
    createdat            DATE NOT NULL DEFAULT now()::DATE,
    -- Address Info
    street               TEXT    NOT NULL,
    "streetNumber"       TEXT    NOT NULL,
    city                 TEXT    NOT NULL,
    zip                  TEXT    NOT NULL,
    -- Account Info
    iban                 TEXT,
    owner                TEXT,
    sepa                 BOOLEAN NOT NULL DEFAULT false,
    "bankName"           TEXT,
    -- Contact Info
    phone                TEXT,
    email                TEXT    NOT NULL,
    website              TEXT,

    online               BOOLEAN NOT NULL DEFAULT false
);
