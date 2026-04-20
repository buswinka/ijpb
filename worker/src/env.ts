// Environment bindings.

export interface Env {
    SUPABASE_URL: string;
    SUPABASE_SERVICE_KEY: string;
    ANTHROPIC_API_KEY: string;

    // Per-device rate limit binding (see wrangler.toml).
    CHAT_RATELIMIT: RateLimit;
}

interface RateLimit {
    limit(opts: { key: string }): Promise<{ success: boolean }>;
}
