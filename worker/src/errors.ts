// Centralized error codes + JSON response helpers.
// The plugin should switch on `code` (stable) rather than `message` (human-readable).

export type ErrorCode =
    | 'bad_request'
    | 'missing_device_uuid'
    | 'license_invalid'              // LS says the key isn't valid
    | 'license_inactive'             // LS says the subscription has lapsed
    | 'license_already_bound'        // /activate: bound to a different device (here or in LS)
    | 'license_device_mismatch'      // /chat:     key doesn't match this device
    | 'free_quota_exhausted'
    | 'daily_quota_exhausted'
    | 'monthly_quota_exhausted'
    | 'rate_limited'
    | 'payload_too_large'
    | 'upstream_error'               // Anthropic or LS failed
    | 'internal_error';

export function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: { 'content-type': 'application/json' },
    });
}

export function errorResponse(code: ErrorCode, message: string, status: number): Response {
    return jsonResponse({ error: { code, message } }, status);
}
