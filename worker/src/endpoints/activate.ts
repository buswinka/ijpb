// POST /activate — bind a license key to a device via LemonSqueezy.
//
// Request:  { device_uuid, license_key }
// Response: { bound: true, tier: 'paid' }
//
// Flow:
//   1. Check our DB: is this key already bound locally?
//        - bound to THIS device → idempotent success (user hit activate twice).
//        - bound to a DIFFERENT device → reject (shouldn't happen since LS would
//          also have an instance, but defense-in-depth).
//   2. Call LS activateLicense with instance_name = device_uuid.
//        - LS enforces the "activations per license = 1" rule server-side.
//        - On success we get an instance_id, which we persist.
//        - On failure we map to license_already_bound or license_invalid.
//   3. Insert row into our licenses table.

import type { Env } from '../env';
import { jsonResponse, errorResponse } from '../errors';
import { Supabase } from '../supabase';
import { activateLicense } from '../lemonsqueezy';

interface ActivateBody {
    device_uuid?: string;
    license_key?: string;
}

export async function handleActivate(req: Request, env: Env): Promise<Response> {
    const body = (await req.json().catch(() => ({}))) as ActivateBody;
    if (!body.device_uuid) return errorResponse('missing_device_uuid', 'device_uuid is required', 400);
    if (!body.license_key) return errorResponse('bad_request', 'license_key is required', 400);

    const db = new Supabase(env);
    const keyEnc = encodeURIComponent(body.license_key);

    // 1. Check for existing local binding.
    const existing = await db.select<{ device_uuid: string }>(
        'licenses', `license_key=eq.${keyEnc}&select=device_uuid`,
    );
    if (existing.length > 0) {
        if (existing[0].device_uuid === body.device_uuid) {
            return jsonResponse({ bound: true, tier: 'paid' });
        }
        return errorResponse('license_already_bound', 'license is bound to another device', 403);
    }

    // 2. Activate on LS.
    const result = await activateLicense(env, body.license_key, body.device_uuid);
    if (!result.ok) {
        // LS's error messages tell us why. We map them to our codes as best we can.
        const msg = (result.error ?? '').toLowerCase();
        if (msg.includes('activation limit')) {
            return errorResponse('license_already_bound', 'license is bound to another device', 403);
        }
        return errorResponse('license_invalid', result.error ?? 'license invalid', 403);
    }

    // 3. Persist locally.
    await db.insert('licenses', {
        license_key:    body.license_key,
        device_uuid:    body.device_uuid,
        ls_instance_id: result.instance_id!,
    });
    await db.rpc('touch_device', { p_device_uuid: body.device_uuid });

    return jsonResponse({ bound: true, tier: 'paid' });
}
