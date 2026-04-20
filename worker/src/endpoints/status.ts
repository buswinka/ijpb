// POST /status — returns tier and remaining quota.
//
// Request:  { device_uuid, license_key? }
// Response: { tier, remaining, limit, resets_at? }
//
// For the paid tier we validate the license against LS (cached 60s), so this
// reflects LS state accurately — cancelled or expired subs show up here.

import type { Env } from '../env';
import { CONFIG } from '../config';
import { jsonResponse, errorResponse } from '../errors';
import { Supabase, utcDateString } from '../supabase';
import { validateLicense } from '../lemonsqueezy';

interface StatusBody {
    device_uuid?: string;
    license_key?: string;
}

export async function handleStatus(req: Request, env: Env): Promise<Response> {
    const body = (await req.json().catch(() => ({}))) as StatusBody;
    if (!body.device_uuid) return errorResponse('missing_device_uuid', 'device_uuid is required', 400);

    const db = new Supabase(env);
    await db.rpc('touch_device', { p_device_uuid: body.device_uuid });

    if (body.license_key) {
        const keyEnc = encodeURIComponent(body.license_key);

        // Local binding lookup — find the ls_instance_id for this license.
        const rows = await db.select<{ device_uuid: string; ls_instance_id: string }>(
            'licenses', `license_key=eq.${keyEnc}&select=device_uuid,ls_instance_id`,
        );
        if (rows.length === 0) {
            return errorResponse('license_invalid', 'license not activated on any device', 404);
        }
        const lic = rows[0];
        if (lic.device_uuid !== body.device_uuid) {
            return errorResponse('license_device_mismatch', 'license is bound to another device', 403);
        }

        // Ask LS if the license + instance is still valid right now.
        const v = await validateLicense(env, body.license_key, lic.ls_instance_id);
        if (!v.valid || !v.subscription_active) {
            return errorResponse('license_inactive', v.error ?? 'subscription not active', 403);
        }

        const today = utcDateString();
        const dailyRows = await db.select<{ count: number }>(
            'daily_usage', `license_key=eq.${keyEnc}&usage_date=eq.${today}&select=count`,
        );
        const dailyUsed = dailyRows[0]?.count ?? 0;

        // Monthly usage: ask the DB which cycle we're in, then look up its points.
        const cycleStart = await db.rpc<string>('current_cycle_start', { p_license_key: body.license_key });
        const monthlyRows = await db.select<{ points_used: number; messages: number }>(
            'monthly_usage',
            `license_key=eq.${keyEnc}&cycle_start_date=eq.${cycleStart}&select=points_used,messages`,
        );
        const monthlyPoints   = monthlyRows[0]?.points_used ?? 0;
        const monthlyMessages = monthlyRows[0]?.messages    ?? 0;

        return jsonResponse({
            tier: 'paid',
            daily: {
                limit:     CONFIG.PAID_DAILY_MESSAGES,
                remaining: Math.max(0, CONFIG.PAID_DAILY_MESSAGES - dailyUsed),
                resets_at: nextUtcMidnightIso(),
            },
            monthly: {
                points_limit:     CONFIG.PAID_MONTHLY_POINTS,
                points_remaining: Math.max(0, CONFIG.PAID_MONTHLY_POINTS - monthlyPoints),
                messages_used:    monthlyMessages,
                cycle_start:      cycleStart,
            },
        });
    }

    // Free path.
    const dev = await db.select<{ free_messages_used: number }>(
        'devices', `device_uuid=eq.${encodeURIComponent(body.device_uuid)}&select=free_messages_used`,
    );
    const used = dev[0]?.free_messages_used ?? 0;
    return jsonResponse({
        tier:      'free',
        limit:     CONFIG.FREE_LIFETIME_MESSAGES,
        remaining: Math.max(0, CONFIG.FREE_LIFETIME_MESSAGES - used),
    });
}

function nextUtcMidnightIso(): string {
    const d = new Date();
    d.setUTCHours(24, 0, 0, 0);
    return d.toISOString();
}
