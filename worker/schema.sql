-- ImageJ LLM Backend — Supabase schema
-- Run this in the Supabase SQL Editor (Project > SQL Editor > New query).
--
-- Design note: with LemonSqueezy validation as the source of truth for
-- subscription status, the licenses table is now just a thin record of
-- "which device is this license bound to, and what's its LS activation
-- instance ID." All status/customer/subscription info lives in LS.

-- ============================================================================
-- TABLES
-- ============================================================================

-- Device-license binding. One row per license_key, created on first activation.
-- If LS rejects activation (already activated elsewhere), no row is created here.
create table if not exists licenses (
    license_key       text primary key,
    device_uuid       text not null,                 -- the device this key is bound to
    ls_instance_id    text not null,                 -- LS activation instance ID (for deactivation)
    activated_at      timestamptz not null default now(),
    created_at        timestamptz not null default now()
);

create index if not exists licenses_device_idx on licenses(device_uuid);

-- Every device that has ever called the API.
create table if not exists devices (
    device_uuid         text primary key,
    free_messages_used  int  not null default 0,    -- counts toward 25 lifetime cap
    first_seen_at       timestamptz not null default now(),
    last_seen_at        timestamptz not null default now()
);

-- Per-day usage counter for subscribers. PK includes date → UTC-day reset is free.
create table if not exists daily_usage (
    license_key  text not null references licenses(license_key) on delete cascade,
    usage_date   date not null,                      -- UTC date
    count        int  not null default 0,
    primary key (license_key, usage_date)
);

-- Per-billing-cycle token usage counter. Cost is tracked in "points" where
-- 10,000 points = $1 USD. At Sonnet 4.6 pricing:
--   input_points  = input_tokens  * 3    ($3 per 1M tokens)
--   output_points = output_tokens * 15   ($15 per 1M tokens)
-- Cycle is identified by cycle_start_date — the first day of the current
-- billing cycle (derived from the license's activation_day_of_month).
-- When a new cycle begins there's simply no row yet, which is the reset.
create table if not exists monthly_usage (
    license_key       text not null references licenses(license_key) on delete cascade,
    cycle_start_date  date not null,
    points_used       bigint not null default 0,
    messages          int    not null default 0,    -- message count, for analytics
    primary key (license_key, cycle_start_date)
);


-- Append-only log of every LLM call.
create table if not exists message_log (
    id             bigserial primary key,
    device_uuid    text not null,
    license_key    text,
    tier           text not null check (tier in ('free','paid')),
    input_tokens   int,
    output_tokens  int,
    model          text,
    error          text,
    created_at     timestamptz not null default now()
);

create index if not exists message_log_device_idx  on message_log(device_uuid, created_at desc);
create index if not exists message_log_license_idx on message_log(license_key, created_at desc);

-- ============================================================================
-- HELPER FUNCTIONS
-- ============================================================================

create or replace function increment_free_usage(p_device_uuid text)
returns int
language plpgsql
as $$
declare
    new_count int;
begin
    insert into devices (device_uuid, free_messages_used, last_seen_at)
    values (p_device_uuid, 1, now())
    on conflict (device_uuid) do update
        set free_messages_used = devices.free_messages_used + 1,
            last_seen_at       = now()
    returning free_messages_used into new_count;
    return new_count;
end;
$$;

create or replace function increment_paid_usage(p_license_key text)
returns int
language plpgsql
as $$
declare
    new_count int;
    today date := (now() at time zone 'utc')::date;
begin
    insert into daily_usage (license_key, usage_date, count)
    values (p_license_key, today, 1)
    on conflict (license_key, usage_date) do update
        set count = daily_usage.count + 1
    returning count into new_count;
    return new_count;
end;
$$;

-- Compute the start date of the current billing cycle for a license.
-- The anchor is the day-of-month that the license was activated. If that
-- day doesn't exist in the current month (e.g. activated on the 31st,
-- currently February), we clamp to the last day of the month.
create or replace function current_cycle_start(p_license_key text)
returns date
language plpgsql
stable
as $$
declare
    activated  timestamptz;
    anchor_day int;
    today      date := (now() at time zone 'utc')::date;
    candidate  date;
    last_day   int;
begin
    select activated_at into activated from licenses where license_key = p_license_key;
    if activated is null then
        return today;  -- no license row; shouldn't happen on the hot path
    end if;

    anchor_day := extract(day from (activated at time zone 'utc'))::int;

    -- Candidate = the anchor day in the current month.
    last_day := extract(day from (date_trunc('month', today) + interval '1 month - 1 day'))::int;
    candidate := make_date(
        extract(year from today)::int,
        extract(month from today)::int,
        least(anchor_day, last_day)
    );

    -- If that date is still in the future, we're actually still in the
    -- PREVIOUS cycle — so back up a month.
    if candidate > today then
        candidate := candidate - interval '1 month';
    end if;
    return candidate;
end;
$$;

-- Atomically add points + 1 message to the current cycle's counter.
-- Returns the new points_used total.
create or replace function increment_monthly_usage(p_license_key text, p_points bigint)
returns bigint
language plpgsql
as $$
declare
    new_total bigint;
    cycle     date := current_cycle_start(p_license_key);
begin
    insert into monthly_usage (license_key, cycle_start_date, points_used, messages)
    values (p_license_key, cycle, p_points, 1)
    on conflict (license_key, cycle_start_date) do update
        set points_used = monthly_usage.points_used + p_points,
            messages    = monthly_usage.messages + 1
    returning points_used into new_total;
    return new_total;
end;
$$;


create or replace function touch_device(p_device_uuid text)
returns void
language plpgsql
as $$
begin
    insert into devices (device_uuid, last_seen_at)
    values (p_device_uuid, now())
    on conflict (device_uuid) do update
        set last_seen_at = now();
end;
$$;

-- Admin helper: remove a license binding locally. (You must ALSO deactivate the
-- instance on LemonSqueezy's side — see SETUP.md.)
create or replace function admin_unbind_license(p_license_key text)
returns void
language plpgsql
as $$
begin
    delete from licenses where license_key = p_license_key;
end;
$$;

-- ============================================================================
-- ROW LEVEL SECURITY
-- ============================================================================
-- Enable RLS on all tables with no policies → blocks all anon/authenticated
-- access. The Worker uses service_role which bypasses RLS.

alter table licenses       enable row level security;
alter table devices        enable row level security;
alter table daily_usage    enable row level security;
alter table monthly_usage  enable row level security;
alter table message_log    enable row level security;
