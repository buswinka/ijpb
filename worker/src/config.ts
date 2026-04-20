// All tunables live here so they're easy to find and change.

export const CONFIG = {
    // Quotas
    FREE_LIFETIME_MESSAGES: 25,
    PAID_DAILY_MESSAGES:    50,    // soft UX cap; monthly points is the hard cost cap
    PAID_MONTHLY_POINTS:    75000, // 75000 points = $7.50 (= half of $15/mo subscription)

    // Per-message token caps (enforced before calling Anthropic).
    // Input cap must cover the full system prompt (~1500 tok) + history; output cap
    // must cover thinking budget + a complete script + explanation.
    // Extended thinking requires max_tokens > budget_tokens; 16000 matches ClaudeClient.
    MAX_INPUT_TOKENS:       16000,
    MAX_OUTPUT_TOKENS:      16000,
    THINKING_BUDGET_TOKENS: 10000,

    // Also bound the request body size as a cheap pre-check (bytes).
    // 4K tokens ≈ 16KB of text; give some headroom for JSON overhead + history.
    MAX_REQUEST_BODY_BYTES: 64 * 1024,

    // Anthropic model
    ANTHROPIC_MODEL: 'claude-sonnet-4-6',
    ANTHROPIC_API_URL: 'https://api.anthropic.com/v1/messages',
    ANTHROPIC_VERSION: '2023-06-01',

    // Anthropic pricing in "points" per token, where 10000 points = $1 USD.
    // Sonnet 4.6: $3/M input, $15/M output → 3 and 15 points per token respectively.
    INPUT_POINTS_PER_TOKEN:  3,
    OUTPUT_POINTS_PER_TOKEN: 15,

    // System prompt for the ImageJ assistant.
    SYSTEM_PROMPT:
        'You are a helpful assistant embedded in an ImageJ plugin. ' +
        'Help users with image analysis, plugin usage, and scientific computing questions. ' +
        'Be concise and practical. If asked about something outside ImageJ/image analysis, ' +
        'answer briefly but gently steer back to what the plugin is for.',
} as const;

// Compute the cost of a message in points.
export function messagePoints(inputTokens: number, outputTokens: number): number {
    return inputTokens  * CONFIG.INPUT_POINTS_PER_TOKEN
         + outputTokens * CONFIG.OUTPUT_POINTS_PER_TOKEN;
}
