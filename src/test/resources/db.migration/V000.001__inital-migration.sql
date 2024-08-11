CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE SCHEMA IF NOT EXISTS eda;

CREATE TABLE IF NOT EXISTS eda.tenantconfig
(
    tenant   VARCHAR PRIMARY KEY,
    domain   VARCHAR NOT NULL,
    host     VARCHAR NOT NULL,
    imapPort INTEGER NOT NULL,
    smtpPort INTEGER NOT NULL,
    smtpHost VARCHAR NOT NULL,
    username VARCHAR NOT NULL,
    pass     VARCHAR NOT NULL,
    imap_security VARCHAR NOT NULL,
    smtp_security VARCHAR NOT NULL,
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