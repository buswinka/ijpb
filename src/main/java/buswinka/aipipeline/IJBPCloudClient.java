package buswinka.aipipeline;

import java.io.*;
import java.net.*;
import java.util.*;

/**
 * LLMClient that routes requests through the IJBP Cloud Cloudflare Worker.
 *
 * The worker wraps Claude and handles auth, quota enforcement, and billing.
 * Quota state is authoritative on the server; this client caches the last
 * known values from /chat and /status responses for UI display only.
 *
 * Endpoints:
 *   GET  /           — health check
 *   POST /chat       — { device_uuid, license_key?, system_prompt?, messages[] }
 *   POST /activate   — { device_uuid, license_key }
 *   POST /status     — { device_uuid, license_key? }
 */
public class IJBPCloudClient implements LLMClient {

    static final String BASE_URL     = "https://imagej-llm-backend.buswinka.workers.dev";
    private static final String CHAT_URL     = BASE_URL + "/chat";
    private static final String ACTIVATE_URL = BASE_URL + "/activate";
    private static final String STATUS_URL   = BASE_URL + "/status";

    private int connectTimeoutMs = 10_000;
    private int readTimeoutMs    = 300_000; // 5 min — extended thinking can be slow

    // Cached from the last /chat or /status response; -1 means not yet known.
    private volatile String lastTier      = null;
    private volatile int    lastRemaining = -1;

    // -------------------------------------------------------------------------
    // LLMClient interface
    // -------------------------------------------------------------------------

    @Override
    public void configure(Map<String, String> settings) {
        if (settings.containsKey("timeout")) {
            try { readTimeoutMs = Integer.parseInt(settings.get("timeout")); }
            catch (NumberFormatException ignored) {}
        }
    }

    @Override
    public String getProviderName() { return "IJBP Cloud"; }

    /** Returns true when GET / responds with 2xx. */
    @Override
    public boolean isAvailable() {
        try {
            URL url = new URL(BASE_URL + "/");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5_000);
            conn.setReadTimeout(5_000);
            int code = conn.getResponseCode();
            conn.disconnect();
            return code >= 200 && code < 300;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public GeneratedPipeline generate(String systemPrompt, List<String[]> history,
                                      String userMessage, ScriptLanguage target) throws Exception {
        String deviceId   = MachineId.getMachineId();
        String licenseKey = TierManager.getLicenseKey();

        String body     = buildRequestBody(deviceId, licenseKey, systemPrompt, history, userMessage);
        String response = post(CHAT_URL, body);
        DebugLog.log("CloudClient", "=== RAW RESPONSE ===\n%s", response);

        // Surface structured server errors (quota exhausted, license invalid, etc.)
        String errCode = JsonHelper.extractField(response, "error");
        if (errCode != null) {
            String msg = JsonHelper.extractField(response, "message");
            throw new Exception(msg != null ? msg : "IJBP Cloud error: " + errCode);
        }

        GeneratedPipeline result = parseReply(response, target);
        if (result == null) {
            // Retry with a constrained prompt — mirrors ClaudeClient's retry strategy.
            String retryUser = userMessage
                + "\n\nReturn ONLY a JSON object with fields: title, explanation, script, language."
                + " No markdown, no explanation outside the JSON.";
            body     = buildRequestBody(deviceId, licenseKey, systemPrompt, history, retryUser);
            response = post(CHAT_URL, body);
            DebugLog.log("CloudClient", "=== RAW RESPONSE (retry) ===\n%s", response);
            result   = parseReply(response, target);
        }
        if (result == null) throw new Exception("IJBP Cloud returned unparseable response after retry");
        return result;
    }

    // -------------------------------------------------------------------------
    // Request building
    // -------------------------------------------------------------------------

    // Marker that separates dynamic content (intro + Fiji env, before this line)
    // from stable content (behavior + output format, from this line onwards).
    // Dynamic content is sent uncached; stable content is sent with cache_control.
    private static final String CTX_END = "=== Behavior ===";

    private String buildRequestBody(String deviceId, String licenseKey,
                                    String systemPrompt, List<String[]> history,
                                    String userMessage) {
        String[] parts = splitSystemPrompt(systemPrompt);
        String staticPrompt = parts[0];
        String fijiContext  = parts[1];

        DebugLog.log("CloudClient", "=== PROMPT SPLIT ===");
        DebugLog.log("CloudClient", "--- context (uncached, sent first) ---\n%s", fijiContext != null ? fijiContext : "(empty)");
        DebugLog.log("CloudClient", "--- system_prompt (cached, sent second) ---\n%s", staticPrompt != null ? staticPrompt : "(empty)");
        DebugLog.log("CloudClient", "--- user message ---\n%s", userMessage);
        if (history != null && !history.isEmpty()) {
            DebugLog.log("CloudClient", "--- history (%d turns) ---", history.size());
            for (String[] msg : history) {
                DebugLog.log("CloudClient", "  [%s] %s", msg[0], msg[1]);
            }
        }

        StringBuilder sb = new StringBuilder("{");
        sb.append("\"device_uuid\":\"").append(JsonHelper.escape(deviceId != null ? deviceId : "")).append("\"");
        if (licenseKey != null && !licenseKey.isEmpty()) {
            sb.append(",\"license_key\":\"").append(JsonHelper.escape(licenseKey)).append("\"");
        }
        if (staticPrompt != null && !staticPrompt.isEmpty()) {
            sb.append(",\"system_prompt\":\"").append(JsonHelper.escape(staticPrompt)).append("\"");
        }
        if (fijiContext != null && !fijiContext.isEmpty()) {
            sb.append(",\"context\":\"").append(JsonHelper.escape(fijiContext)).append("\"");
        }
        sb.append(",\"messages\":").append(JsonHelper.buildMessagesArray(history, userMessage));
        sb.append("}");

        DebugLog.log("CloudClient", "=== RAW REQUEST BODY ===\n%s", sb.toString());
        return sb.toString();
    }

    /**
     * Splits the full system prompt into [staticPart, dynamicContext].
     *
     * dynamicContext = everything from the start of the prompt up to (but not
     * including) CTX_END — i.e. the intro text + the Fiji environment section.
     * This block changes every call and is sent uncached.
     *
     * staticPart = CTX_END and everything after it — the Behavior, Output Format,
     * and Script Constraints sections that never change. This block is sent with
     * cache_control so Anthropic can cache it across calls.
     *
     * Sending dynamicContext first preserves the original prompt order:
     *   [intro] → [=== Current Fiji Environment ===] → [=== Behavior ===] → …
     */
    private static String[] splitSystemPrompt(String systemPrompt) {
        if (systemPrompt == null) return new String[]{null, null};

        int endIdx = systemPrompt.indexOf(CTX_END);
        if (endIdx < 0) {
            // No closing marker — entire prompt is dynamic (no caching benefit).
            return new String[]{null, systemPrompt};
        }

        String dynamicContext = systemPrompt.substring(0, endIdx).trim();
        String staticPart     = systemPrompt.substring(endIdx).trim();
        return new String[]{staticPart, dynamicContext};
    }

    // -------------------------------------------------------------------------
    // Response parsing
    // -------------------------------------------------------------------------

    /**
     * Parses a /chat response: { reply, tier, remaining }.
     * Updates the cached quota fields, then extracts title/script/explanation
     * from the reply text using the same logic as ClaudeClient.
     */
    private GeneratedPipeline parseReply(String httpResponse, ScriptLanguage target) {
        updateQuotaCache(httpResponse);

        String reply = JsonHelper.extractField(httpResponse, "reply");
        if (reply == null) return null;

        String content = JsonHelper.stripCodeFences(reply);
        String title  = JsonHelper.extractField(content, "title");
        String expl   = JsonHelper.extractField(content, "explanation");
        String script = JsonHelper.extractField(content, "script");

        if (title != null && script != null) {
            ScriptLanguage lang = parseLanguage(JsonHelper.extractField(content, "language"), target);
            return new GeneratedPipeline(title, expl != null ? expl : "", script, lang);
        }
        // Partial JSON means the LLM started but didn't finish — signal retry.
        if (content.contains("\"script\"") || content.contains("\"title\"")) return null;
        // Plain conversational reply.
        return GeneratedPipeline.conversational(content.trim());
    }

    private void updateQuotaCache(String json) {
        String tier = JsonHelper.extractField(json, "tier");
        if (tier != null) lastTier = tier;
        int rem = JsonHelper.extractIntField(json, "remaining");
        if (rem >= 0) lastRemaining = rem;
    }

    private static ScriptLanguage parseLanguage(String lang, ScriptLanguage fallback) {
        if ("python".equalsIgnoreCase(lang)) return ScriptLanguage.PYTHON;
        if ("ijm".equalsIgnoreCase(lang))    return ScriptLanguage.IJM;
        return fallback;
    }

    // -------------------------------------------------------------------------
    // Cached quota accessors (for UI display)
    // -------------------------------------------------------------------------

    /** Last tier string returned by the server ("free" or "paid"), or null if not yet known. */
    public String getLastTier()      { return lastTier; }

    /** Last remaining-message count from the server, or -1 if not yet known. */
    public int    getLastRemaining() { return lastRemaining; }

    /**
     * Calls POST /status and updates this instance's cached tier/remaining fields.
     * Intended for background prefetch at window open; silently ignores errors.
     */
    public void refreshStatus(String deviceUuid, String licenseKey) {
        try {
            StringBuilder sb = new StringBuilder("{\"device_uuid\":\"")
                .append(JsonHelper.escape(deviceUuid)).append("\"");
            if (licenseKey != null && !licenseKey.isEmpty()) {
                sb.append(",\"license_key\":\"").append(JsonHelper.escape(licenseKey)).append("\"");
            }
            sb.append("}");
            String response = postRaw(STATUS_URL, sb.toString(), 10_000);
            updateQuotaCache(response);
        } catch (Exception ignored) {}
    }

    // -------------------------------------------------------------------------
    // Static helpers (called from PreferencesDialog without an instance)
    // -------------------------------------------------------------------------

    /**
     * POST /activate — binds a license key to this device via the server.
     * Idempotent: calling it again for the same device returns success quietly.
     *
     * @return null on success, a human-readable error string on failure.
     */
    public static String activate(String deviceUuid, String licenseKey) {
        try {
            String body = "{\"device_uuid\":\"" + JsonHelper.escape(deviceUuid)
                        + "\",\"license_key\":\"" + JsonHelper.escape(licenseKey) + "\"}";
            String response = postRaw(ACTIVATE_URL, body, 10_000);
            String errCode = JsonHelper.extractField(response, "error");
            if (errCode != null) {
                String msg = JsonHelper.extractField(response, "message");
                return msg != null ? msg : "Activation failed: " + errCode;
            }
            return null; // success
        } catch (Exception e) {
            return "Activation failed: " + e.getMessage();
        }
    }

    /**
     * POST /status — returns a human-readable status string for display in the
     * Test Connection dialog (tier, remaining messages, etc.).
     */
    public static String fetchStatus(String deviceUuid, String licenseKey) {
        try {
            StringBuilder sb = new StringBuilder("{\"device_uuid\":\"")
                .append(JsonHelper.escape(deviceUuid)).append("\"");
            if (licenseKey != null && !licenseKey.isEmpty()) {
                sb.append(",\"license_key\":\"").append(JsonHelper.escape(licenseKey)).append("\"");
            }
            sb.append("}");

            String response = postRaw(STATUS_URL, sb.toString(), 10_000);
            String errCode = JsonHelper.extractField(response, "error");
            if (errCode != null) {
                String msg = JsonHelper.extractField(response, "message");
                return "Error: " + (msg != null ? msg : errCode);
            }

            String tier = JsonHelper.extractField(response, "tier");
            if ("paid".equals(tier)) {
                // Response: { tier, daily: { limit, remaining, resets_at }, monthly: {...} }
                int remaining = JsonHelper.extractIntField(response, "remaining");
                int limit     = JsonHelper.extractIntField(response, "limit");
                StringBuilder out = new StringBuilder("Tier: Pro");
                if (remaining >= 0) {
                    out.append("  |  Messages today remaining: ").append(remaining);
                    if (limit > 0) out.append(" / ").append(limit);
                }
                return out.toString();
            } else {
                // Response: { tier, limit, remaining }
                int remaining = JsonHelper.extractIntField(response, "remaining");
                int limit     = JsonHelper.extractIntField(response, "limit");
                StringBuilder out = new StringBuilder("Tier: Free");
                if (remaining >= 0) {
                    out.append("  |  Lifetime messages remaining: ").append(remaining);
                    if (limit > 0) out.append(" / ").append(limit);
                }
                return out.toString();
            }
        } catch (Exception e) {
            return "Status unavailable: " + e.getMessage();
        }
    }

    // -------------------------------------------------------------------------
    // HTTP helpers
    // -------------------------------------------------------------------------

    /** Instance POST with the configured read/connect timeouts. */
    private String post(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }

            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
            if (is == null) throw new IOException("HTTP " + code + " with no response body");

            StringBuilder resp = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) resp.append(line).append("\n");
            }

            // Return error JSON to caller for structured handling; throw on bare HTTP errors.
            if (code < 200 || code >= 300) {
                String errBody = resp.toString();
                if (JsonHelper.extractField(errBody, "error") != null) return errBody;
                throw new IOException("HTTP " + code + ": " + errBody.trim());
            }
            return resp.toString();
        } finally {
            conn.disconnect();
        }
    }

    /** Short-timeout static POST used by activate() and fetchStatus(). */
    private static String postRaw(String urlStr, String body, int timeoutMs) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300)
                ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder resp = new StringBuilder();
            if (is != null) {
                try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) resp.append(line).append("\n");
                }
            }
            return resp.toString();
        } finally {
            conn.disconnect();
        }
    }
}
