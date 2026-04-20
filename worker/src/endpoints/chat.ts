// POST /chat — the hot path.
//
// Request:  { device_uuid, license_key?, messages: [{role, content}, ...] }
// Response: { reply, tier, remaining }

import type { Env } from '../env';
import { CONFIG, messagePoints } from '../config';
import { jsonResponse, errorResponse } from '../errors';
import { Supabase, utcDateString } from '../supabase';
import { callAnthropic, estimateTokens, type AnthropicMessage } from '../anthropic';
import { validateLicense } from '../lemonsqueezy';

interface ChatBody {
    device_uuid?:   string;
    license_key?:   string;
    system_prompt?: string;
    context?:       string;   // dynamic per-call content (e.g. live Fiji state); not cached
    messages?:      AnthropicMessage[];
}

export async function handleChat(req: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    // ---- 1. Parse + validate ----
    const contentLength = parseInt(req.headers.get('content-length') ?? '0', 10);
    if (contentLength > CONFIG.MAX_REQUEST_BODY_BYTES) {
        return errorResponse('payload_too_large', 'request body too large', 413);
    }

    const body = (await req.json().catch(() => ({}))) as ChatBody;
    if (!body.device_uuid) return errorResponse('missing_device_uuid', 'device_uuid is required', 400);
    if (!Array.isArray(body.messages) || body.messages.length === 0) {
        return errorResponse('bad_request', 'messages array is required and non-empty', 400);
    }
    for (const m of body.messages) {
        if (m.role !== 'user' && m.role !== 'assistant') return errorResponse('bad_request', 'invalid message role', 400);
        if (typeof m.content !== 'string' || !m.content) return errorResponse('bad_request', 'invalid message content', 400);
    }
    const totalInputEst = body.messages.reduce((n, m) => n + estimateTokens(m.content), 0)
        + estimateTokens(body.system_prompt ?? CONFIG.SYSTEM_PROMPT)
        + estimateTokens(body.context ?? '');
    if (totalInputEst > CONFIG.MAX_INPUT_TOKENS) {
        return errorResponse('payload_too_large', `input too long (est. ${totalInputEst} tokens, cap ${CONFIG.MAX_INPUT_TOKENS})`, 413);
    }

    // ---- 2. Rate limit ----
    const rl = await env.CHAT_RATELIMIT.limit({ key: body.device_uuid });
    if (!rl.success) return errorResponse('rate_limited', 'too many requests, slow down', 429);

    const db = new Supabase(env);
    const tier: 'free' | 'paid' = body.license_key ? 'paid' : 'free';

    // ---- 3. Paid-tier verification + 4. Quota check ----
    if (tier === 'paid') {
        const keyEnc = encodeURIComponent(body.license_key!);

        // Look up binding.
        const rows = await db.select<{ device_uuid: string; ls_instance_id: string }>(
            'licenses', `license_key=eq.${keyEnc}&select=device_uuid,ls_instance_id`,
        );
        if (rows.length === 0)                              return errorResponse('license_invalid', 'license not activated on any device', 404);
        const lic = rows[0];
        if (lic.device_uuid !== body.device_uuid)           return errorResponse('license_device_mismatch', 'license not bound to this device', 403);

        // Validate against LS (cached 60s).
        const v = await validateLicense(env, body.license_key!, lic.ls_instance_id);
        if (!v.valid || !v.subscription_active) {
            return errorResponse('license_inactive', v.error ?? 'subscription not active', 403);
        }

        // Daily quota.
        const today = utcDateString();
        const usage = await db.select<{ count: number }>(
            'daily_usage', `license_key=eq.${keyEnc}&usage_date=eq.${today}&select=count`,
        );
        if ((usage[0]?.count ?? 0) >= CONFIG.PAID_DAILY_MESSAGES) {
            return errorResponse('daily_quota_exhausted', 'daily message limit reached, resets at UTC midnight', 429);
        }

        // Monthly token-cost cap (post-check: we block the NEXT call if a prior
        // call pushed us over). Derive the current cycle start from the license's
        // activation date via SQL function.
        const cycleStart = await db.rpc<string>('current_cycle_start', { p_license_key: body.license_key });
        const monthly = await db.select<{ points_used: number }>(
            'monthly_usage',
            `license_key=eq.${keyEnc}&cycle_start_date=eq.${cycleStart}&select=points_used`,
        );
        if ((monthly[0]?.points_used ?? 0) >= CONFIG.PAID_MONTHLY_POINTS) {
            return errorResponse('monthly_quota_exhausted', 'monthly token budget reached, resets next billing cycle', 429);
        }
    } else {
        const dev = await db.select<{ free_messages_used: number }>(
            'devices', `device_uuid=eq.${encodeURIComponent(body.device_uuid)}&select=free_messages_used`,
        );
        if ((dev[0]?.free_messages_used ?? 0) >= CONFIG.FREE_LIFETIME_MESSAGES) {
            return errorResponse('free_quota_exhausted', 'free messages used up — subscribe to continue', 403);
        }
    }

    // ---- 5. Call Anthropic ----
    let result;
    try {
        result = await callAnthropic(env, body.messages, body.system_prompt, body.context);
    } catch (err) {
        console.error('anthropic call failed', err);
        void db.insert('message_log', {
            device_uuid: body.device_uuid,
            license_key: body.license_key ?? null,
            tier,
            model:       CONFIG.ANTHROPIC_MODEL,
            error:       String(err).slice(0, 500),
        }).catch(() => {});
        return errorResponse('upstream_error', 'LLM provider failed, try again', 502);
    }

    // ---- 6. Atomic increments ----
    let newCount: number;
    if (tier === 'paid') {
        newCount = await db.rpc<number>('increment_paid_usage', { p_license_key: body.license_key });
        // Also bump the monthly points counter with the real token usage.
        const points = messagePoints(result.input_tokens, result.output_tokens);
        ctx.waitUntil(
            db.rpc('increment_monthly_usage', {
                p_license_key: body.license_key,
                p_points:      points,
            }).catch(err => console.error('monthly increment failed', err)),
        );
    } else {
        newCount = await db.rpc<number>('increment_free_usage', { p_device_uuid: body.device_uuid });
    }
    const limit     = tier === 'paid' ? CONFIG.PAID_DAILY_MESSAGES : CONFIG.FREE_LIFETIME_MESSAGES;
    const remaining = Math.max(0, limit - newCount);

    // ---- 7. Fire-and-forget log ----
    ctx.waitUntil(
        db.insert('message_log', {
            device_uuid:   body.device_uuid,
            license_key:   body.license_key ?? null,
            tier,
            model:         CONFIG.ANTHROPIC_MODEL,
            input_tokens:  result.input_tokens,
            output_tokens: result.output_tokens,
        }).catch(err => console.error('log insert failed', err)),
    );

    return jsonResponse({ reply: result.text, tier, remaining });
}
