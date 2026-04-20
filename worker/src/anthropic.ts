// Minimal Anthropic Messages API client.
// Only implements the non-streaming path we need for v1.

import type { Env } from './env';
import { CONFIG } from './config';

export interface AnthropicMessage {
    role: 'user' | 'assistant';
    content: string;
}

export interface AnthropicResult {
    text:          string;
    input_tokens:  number;
    output_tokens: number;
    stop_reason:   string;
}

// `context` is dynamic per-request content (e.g. live Fiji state) that changes
// every call and must NOT be cached. It is appended as a second system block
// without cache_control so the stable `systemPrompt` block can still cache.
export async function callAnthropic(
    env: Env,
    messages: AnthropicMessage[],
    systemPrompt?: string,
    context?: string,
): Promise<AnthropicResult> {
    const systemText = systemPrompt ?? CONFIG.SYSTEM_PROMPT;
    // context (intro + live Fiji env) is dynamic — send it first, uncached.
    // systemText (behavior + output format) is stable — send it last, cached.
    // This preserves the original prompt order while maximising cache hits.
    const systemBlocks: object[] = [];
    if (context) {
        systemBlocks.push({ type: 'text', text: context });
    }
    systemBlocks.push({ type: 'text', text: systemText, cache_control: { type: 'ephemeral' } });

    const res = await fetch(CONFIG.ANTHROPIC_API_URL, {
        method: 'POST',
        headers: {
            'x-api-key':          env.ANTHROPIC_API_KEY,
            'anthropic-version':  CONFIG.ANTHROPIC_VERSION,
            'anthropic-beta':     'prompt-caching-2024-07-31',
            'content-type':       'application/json',
        },
        body: JSON.stringify({
            model:      CONFIG.ANTHROPIC_MODEL,
            max_tokens: CONFIG.MAX_OUTPUT_TOKENS,
            thinking:   { type: 'enabled', budget_tokens: CONFIG.THINKING_BUDGET_TOKENS },
            system:     systemBlocks,
            messages,
        }),
    });

    if (!res.ok) {
        const errText = await res.text();
        throw new Error(`anthropic ${res.status}: ${errText}`);
    }

    const data = await res.json() as {
        content: Array<{ type: string; text?: string }>;
        usage:   { input_tokens: number; output_tokens: number };
        stop_reason: string;
    };

    // Concatenate all text blocks (there's usually just one).
    const text = data.content
        .filter(b => b.type === 'text' && b.text)
        .map(b => b.text as string)
        .join('');

    return {
        text,
        input_tokens:  data.usage.input_tokens,
        output_tokens: data.usage.output_tokens,
        stop_reason:   data.stop_reason,
    };
}

// Rough token estimate for pre-flight checks (before making the API call).
// Real tokenization would require the tokenizer; this uses the 4-chars-per-token
// rule of thumb which is close enough for a size cap.
export function estimateTokens(text: string): number {
    return Math.ceil(text.length / 4);
}
