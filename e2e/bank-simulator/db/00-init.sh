#!/bin/sh
# postgres:16 first-boot init for the bank simulator (TEST ONLY): runs
# bank-sim.sql with the role names and passwords of this run.
set -eu
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
  -v reader="${BANK_SIM_DB_READER:-modoc_reader}" \
  -v reader_pw="${BANK_SIM_DB_READER_PASSWORD:?set BANK_SIM_DB_READER_PASSWORD}" \
  -v writer="${BANK_SIM_DB_WRITER:-modoc_writer}" \
  -v writer_pw="${BANK_SIM_DB_WRITER_PASSWORD:?set BANK_SIM_DB_WRITER_PASSWORD}" \
  -f /bank-sim/bank-sim.sql
