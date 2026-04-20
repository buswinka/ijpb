// LemonSqueezy License API client.
//
// Endpoints used (all docs at https://docs.lemonsqueezy.com/api/license-api):
//   POST /v1/licenses/validate    — check if a key (+ optional instance) is valid
//   POST /v1/licenses/activate    — create an activation instance on a device
//   POST /v1/licenses/deactivate  — destroy an activation instance
//
// These endpoints use a DIFFERENT authentication model than the rest of the LS
// API — they accept form-encoded data with the license_key as a form field,
// not a bearer token. They're designed to be called from the licensed
// application itself. Our Worker sits in the middle, so we call them
// server-side with the same form-encoded convention.

import type { Env } from './env';

export interface LsValidateResult {
    valid: boolean;
    // Present when valid=true
    subscription_active?: boolean;   // true if LS considers the subscription still paid for
    customer_email?:      string;
    // Present when valid=false
    error?: string;
}

export interface LsActivateResult {
    ok: boolean;
    instance_id?: string;             // only on success
    error?: string;                   // human-readable reason on failure
}

const LS_BASE = 'https://api.lemonsqueezy.com/v1';

// ---------------------------------------------------------------------------
// In-memory validation cache (per Worker isolate).
// Cloudflare reuses isolates across requests, so this provides meaningful
// hit rates when the same user sends chats back-to-back. Expires after 60s.
// ---------------------------------------------------------------------------
interface CacheEntry { result: LsValidateResult; expiresAt: number }
const validationCache = new Map<string, CacheEntry>();
const CACHE_TTL_MS = 60_000;

function cacheKey(key: string, instance: string): string {
    return `${key}|${instance}`;
}

// ---------------------------------------------------------------------------
// Validate a license key (optionally with an instance_id).
// Returns { valid, subscription_active } based on current LS state.
// ---------------------------------------------------------------------------
export async function validateLicense(
    env: Env,
    license_key: string,
    instance_id?: string,
): Promise<LsValidateResult> {
    const ck = cacheKey(license_key, instance_id ?? '');
    const cached = validationCache.get(ck);
    if (cached && cached.expiresAt > Date.now()) return cached.result;

    const form = new URLSearchParams();
    form.set('license_key', license_key);
    if (instance_id) form.set('instance_id', instance_id);

    const res = await fetch(`${LS_BASE}/licenses/validate`, {
        method: 'POST',
        headers: {
            'accept':       'application/json',
            'content-type': 'application/x-www-form-urlencoded',
        },
        body: form.toString(),
    });

    // LS returns 400 for invalid keys (with a JSON body), not a network error.
    const data = await res.json().catch(() => ({})) as any;

    let result: LsValidateResult;
    if (data?.valid === true) {
        // `license_key.status` reflects the overall license state.
        // `meta.customer_email` is the subscriber's email.
        // A subscription-backed license becomes inactive when the sub expires,
        // which LS reports as valid=false on the next validate call.
        const keyStatus: string | undefined = data?.license_key?.status;
        result = {
            valid: true,
            subscription_active: keyStatus === 'active',
            customer_email: data?.meta?.customer_email,
        };
    } else {
        result = {
            valid: false,
            error: data?.error ?? 'license invalid',
        };
    }

    validationCache.set(ck, { result, expiresAt: Date.now() + CACHE_TTL_MS });
    return result;
}

// ---------------------------------------------------------------------------
// Activate a license on a device. instance_name is arbitrary; we use the
// device_uuid so it's easy to audit in the LS dashboard.
// Returns the instance_id on success, or an error if LS rejects (e.g. the
// license has already hit its activation limit — which we set to 1 in the
// product config, so this enforces the one-device rule).
// ---------------------------------------------------------------------------
export async function activateLicense(
    env: Env,
    license_key: string,
    instance_name: string,
): Promise<LsActivateResult> {
    const form = new URLSearchParams();
    form.set('license_key', license_key);
    form.set('instance_name', instance_name);

    const res = await fetch(`${LS_BASE}/licenses/activate`, {
        method: 'POST',
        headers: {
            'accept':       'application/json',
            'content-type': 'application/x-www-form-urlencoded',
        },
        body: form.toString(),
    });

    const data = await res.json().catch(() => ({})) as any;

    if (data?.activated === true && data?.instance?.id) {
        // Bust any cached validation so the new instance gets checked properly.
        for (const k of validationCache.keys()) {
            if (k.startsWith(`${license_key}|`)) validationCache.delete(k);
        }
        return { ok: true, instance_id: String(data.instance.id) };
    }
    return { ok: false, error: data?.error ?? 'activation failed' };
}

// ---------------------------------------------------------------------------
// Deactivate an instance. Used by the admin unbind flow.
// ---------------------------------------------------------------------------
export async function deactivateLicense(
    env: Env,
    license_key: string,
    instance_id: string,
): Promise<{ ok: boolean; error?: string }> {
    const form = new URLSearchParams();
    form.set('license_key', license_key);
    form.set('instance_id', instance_id);

    const res = await fetch(`${LS_BASE}/licenses/deactivate`, {
        method: 'POST',
        headers: {
            'accept':       'application/json',
            'content-type': 'application/x-www-form-urlencoded',
        },
        body: form.toString(),
    });

    const data = await res.json().catch(() => ({})) as any;

    for (const k of validationCache.keys()) {
        if (k.startsWith(`${license_key}|`)) validationCache.delete(k);
    }

    if (data?.deactivated === true) return { ok: true };
    return { ok: false, error: data?.error ?? 'deactivation failed' };
}
