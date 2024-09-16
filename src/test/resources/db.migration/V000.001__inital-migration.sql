CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE SCHEMA IF NOT EXISTS eda;

CREATE TABLE IF NOT EXISTS eda.tenantconfig
(
    tenant   VARCHAR PRIMARY KEY,
    type     VARCHAR NOT NULL DEFAULT 'MAIL',
    domain   VARCHAR,
    host     VARCHAR,
    imapPort INTEGER,
    smtpPort INTEGER,
    smtpHost VARCHAR,
    username VARCHAR,
    pass     VARCHAR,
    imap_security VARCHAR,
    smtp_security VARCHAR,
    active BOOLEAN NOT NULL
);

CREATE TABLE IF NOT EXISTS eda.inbox
(
    id       SERIAL PRIMARY KEY,
    tenant   VARCHAR NOT NULL,
    subject VARCHAR NOT NULL,
    content  bytea NOT NULL,
    received TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS eda.outbox
(
    id       SERIAL PRIMARY KEY,
    tenant   VARCHAR NOT NULL,
    content  bytea NOT NULL,
    sent     TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS eda.conversation
(
    id       VARCHAR PRIMARY KEY,
    conversation JSON
);