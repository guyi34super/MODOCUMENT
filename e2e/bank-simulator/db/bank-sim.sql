-- Bank simulator — the bank's own PostgreSQL (TEST ONLY).
--
-- Run with psql variables (docker init: db/00-init.sh; local: run-local.sh):
--   psql -v reader=<role> -v reader_pw=<pw> -v writer=<role> -v writer_pw=<pw> -f bank-sim.sql
--
-- Layout, as a bank DBA would expose it to MoDocument (plan §6: "the bank's
-- DBA exposes a view; we never run free SQL"):
--   core.customer            the bank's internal table (PII incl. national id) —
--                            MoDocument's reader role has NO grant on it
--   core.ledger              another internal table the agent allow-list and the
--                            DB grants must both keep out of reach
--   integration.customers    the VIEW MoDocument reads (inbound flows),
--                            watermark column updated_at
--   integration.staging_results  the TABLE MoDocument writes (outbound flows),
--                            INSERT or UPSERT on external_ref
-- Roles: :reader (SELECT on the view only), :writer (INSERT/UPDATE on the
-- staging table only). Neither owns anything; neither can read core.*.
\set ON_ERROR_STOP on

CREATE SCHEMA IF NOT EXISTS core;
CREATE SCHEMA IF NOT EXISTS integration;

CREATE TABLE core.customer (
    customer_id   text PRIMARY KEY,
    full_name     text        NOT NULL,
    email         text        NOT NULL,
    national_id   text        NOT NULL,
    segment       text        NOT NULL CHECK (segment IN ('RETAIL', 'SME', 'CORPORATE')),
    balance       numeric(14, 2) NOT NULL,
    opened_on     date        NOT NULL,
    updated_at    timestamptz NOT NULL
);

CREATE TABLE core.ledger (
    entry_id      bigserial PRIMARY KEY,
    customer_id   text        NOT NULL REFERENCES core.customer (customer_id),
    amount        numeric(14, 2) NOT NULL,
    booked_at     timestamptz NOT NULL DEFAULT now()
);

-- 25 deterministic customers; updated_at one day apart so a watermark
-- (updated_at > :last) can be exercised by moving a row forward.
INSERT INTO core.customer (customer_id, full_name, email, national_id, segment, balance, opened_on, updated_at)
SELECT format('CUST-%s', lpad(n::text, 4, '0')),
       format('Customer %s', n),
       format('customer%s@bank-sim.test', n),
       format('N%s', lpad((n * 7919)::text, 13, '0')),
       (ARRAY['RETAIL', 'SME', 'CORPORATE'])[1 + (n - 1) % 3],
       round((1000 + n * 137.25)::numeric, 2),
       date '2020-01-01' + n * 30,
       timestamptz '2024-01-01 00:00:00+00' + n * interval '1 day'
  FROM generate_series(1, 25) AS n;

INSERT INTO core.ledger (customer_id, amount)
SELECT customer_id, balance FROM core.customer;

-- What MoDocument may read: no national id.
CREATE VIEW integration.customers AS
SELECT customer_id, full_name, email, segment, balance, opened_on, updated_at
  FROM core.customer;

-- What MoDocument may write.
CREATE TABLE integration.staging_results (
    staging_id      bigserial PRIMARY KEY,
    external_ref    text        NOT NULL UNIQUE,
    customer_id     text,
    full_name       text,
    email           text,
    status          text        NOT NULL DEFAULT 'VALIDATED',
    source_job_id   bigint,
    payload         jsonb,
    received_at     timestamptz NOT NULL DEFAULT now()
);

SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'reader') AS create_reader \gset
\if :create_reader
CREATE ROLE :"reader" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'reader_pw';
\else
ALTER ROLE :"reader" WITH LOGIN PASSWORD :'reader_pw';
\endif
SELECT NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = :'writer') AS create_writer \gset
\if :create_writer
CREATE ROLE :"writer" LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT PASSWORD :'writer_pw';
\else
ALTER ROLE :"writer" WITH LOGIN PASSWORD :'writer_pw';
\endif

REVOKE ALL ON SCHEMA core FROM PUBLIC;
REVOKE ALL ON ALL TABLES IN SCHEMA core FROM PUBLIC;
GRANT USAGE ON SCHEMA integration TO :"reader", :"writer";
GRANT SELECT ON integration.customers TO :"reader";
-- UPSERT (ON CONFLICT ... DO UPDATE) and RETURNING need SELECT on the table.
GRANT SELECT, INSERT, UPDATE ON integration.staging_results TO :"writer";
GRANT USAGE ON SEQUENCE integration.staging_results_staging_id_seq TO :"writer";
-- Read-only sessions for the reader by default (the connector also sets it).
ALTER ROLE :"reader" SET default_transaction_read_only = on;
