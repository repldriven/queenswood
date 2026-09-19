--liquibase formatted sql

-- The demo digital bank's own records: what the platform does not hold.
-- Referenced from system config as `demo-digital-bank-core/changelog.sql`,
-- a classpath path, so it resolves the same from a checkout and from
-- inside a jar. No balance, no payment status and no transaction is here.

--changeset demo-digital-bank:1
create table sign_ups (
  id             text primary key,
  phone          text        not null,
  status         text        not null,
  party_id       text,
  given_name     text,
  family_name    text,
  created_at     timestamptz not null default now(),
  updated_at     timestamptz not null default now()
);

create table customers (
  id             text primary key,
  party_id       text        not null unique,
  phone          text        not null unique,
  given_name     text        not null,
  family_name    text        not null,
  passcode_hash  text        not null,
  created_at     timestamptz not null default now()
);

create table customer_accounts (
  customer_id    text        not null references customers (id),
  account_id     text        not null unique,
  product_kind   text        not null,
  name           text        not null,
  opened_at      timestamptz not null default now(),
  primary key (customer_id, account_id)
);

create table sessions (
  id             text primary key,
  customer_id    text        not null references customers (id),
  expires_at     timestamptz not null,
  created_at     timestamptz not null default now()
);

create table submissions (
  idempotency_key text primary key,
  sign_up_id      text,
  customer_id     text,
  kind            text        not null,
  request         text        not null,
  response        text,
  created_at      timestamptz not null default now(),
  updated_at      timestamptz not null default now()
);

create index submissions_by_sign_up on submissions (sign_up_id, kind);
