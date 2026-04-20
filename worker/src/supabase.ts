// Tiny Supabase client. We only need PostgREST queries + RPC calls, so no SDK.
// Service_role key bypasses RLS — this client must NEVER be exposed to the plugin.

import type { Env } from './env';

export class Supabase {
    constructor(private env: Env) {}

    private headers(extra: Record<string, string> = {}): HeadersInit {
        return {
            'apikey':        this.env.SUPABASE_SERVICE_KEY,
            'authorization': `Bearer ${this.env.SUPABASE_SERVICE_KEY}`,
            'content-type':  'application/json',
            ...extra,
        };
    }

    // PostgREST table query. Returns parsed JSON array.
    async select<T = any>(table: string, query: string): Promise<T[]> {
        const url = `${this.env.SUPABASE_URL}/rest/v1/${table}?${query}`;
        const res = await fetch(url, { headers: this.headers() });
        if (!res.ok) throw new Error(`supabase select ${table} ${res.status}: ${await res.text()}`);
        return res.json();
    }

    // PostgREST insert. Returns inserted rows.
    async insert<T = any>(table: string, row: Record<string, unknown>): Promise<T[]> {
        const url = `${this.env.SUPABASE_URL}/rest/v1/${table}`;
        const res = await fetch(url, {
            method: 'POST',
            headers: this.headers({ prefer: 'return=representation' }),
            body: JSON.stringify(row),
        });
        if (!res.ok) throw new Error(`supabase insert ${table} ${res.status}: ${await res.text()}`);
        return res.json();
    }

    // PostgREST update with a filter (e.g. "license_key=eq.ABC").
    async update<T = any>(table: string, filter: string, patch: Record<string, unknown>): Promise<T[]> {
        const url = `${this.env.SUPABASE_URL}/rest/v1/${table}?${filter}`;
        const res = await fetch(url, {
            method: 'PATCH',
            headers: this.headers({ prefer: 'return=representation' }),
            body: JSON.stringify(patch),
        });
        if (!res.ok) throw new Error(`supabase update ${table} ${res.status}: ${await res.text()}`);
        return res.json();
    }

    // Upsert (insert or update). on_conflict must match a unique/primary-key column.
    async upsert<T = any>(table: string, row: Record<string, unknown>, onConflict: string): Promise<T[]> {
        const url = `${this.env.SUPABASE_URL}/rest/v1/${table}?on_conflict=${onConflict}`;
        const res = await fetch(url, {
            method: 'POST',
            headers: this.headers({ prefer: 'return=representation,resolution=merge-duplicates' }),
            body: JSON.stringify(row),
        });
        if (!res.ok) throw new Error(`supabase upsert ${table} ${res.status}: ${await res.text()}`);
        return res.json();
    }

    // Call a SQL function defined in schema.sql (e.g. increment_free_usage).
    // Void-returning functions come back as 204 No Content with an empty body,
    // so we parse lazily and return null in that case.
    async rpc<T = any>(fn: string, args: Record<string, unknown>): Promise<T> {
        const url = `${this.env.SUPABASE_URL}/rest/v1/rpc/${fn}`;
        const res = await fetch(url, {
            method: 'POST',
            headers: this.headers(),
            body: JSON.stringify(args),
        });
        if (!res.ok) throw new Error(`supabase rpc ${fn} ${res.status}: ${await res.text()}`);
        const text = await res.text();
        return (text ? JSON.parse(text) : null) as T;
    }
}

// Helper: today's date as a UTC 'YYYY-MM-DD' string, matching Postgres `date`.
export function utcDateString(): string {
    return new Date().toISOString().slice(0, 10);
}