--liquibase formatted sql

-- The demo digital bank's own records: what the platform does not hold.
-- Referenced from system config as `demo-digital-bank/changelog.sql`,
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

--changeset demo-digital-bank:2
-- The key the app sends with a submission, so a repeated tap replays
-- the answer the platform already gave rather than paying twice.
alter table submissions add column client_key text;

create unique index submissions_by_client_key
  on submissions (customer_id, kind, client_key)
  where client_key is not null;

create table payees (
  id                text        primary key,
  customer_id       text        not null references customers (id),
  name              text        not null,
  sort_code         text        not null,
  account_number    text        not null,
  last_paid_at      timestamptz,
  last_paid_amount  bigint,
  created_at        timestamptz not null default now(),
  unique (customer_id, sort_code, account_number)
);

--changeset demo-digital-bank:3
-- What the platform has told the bank. One row per notification, keyed
-- on the platform's notification id so a re-send is recognised as the
-- same one; resolved to a customer once the record is read back; and
-- marked when the customer has been shown it.
create table notifications (
  id             text        primary key,
  delivery_id    text        not null,
  kind           text        not null,
  body           text        not null,
  customer_id    text        references customers (id),
  record         text,
  received_at    timestamptz not null default now(),
  resolved_at    timestamptz,
  seen_at        timestamptz
);

create index notifications_unseen_by_customer
  on notifications (customer_id, received_at)
  where seen_at is null;
