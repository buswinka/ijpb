package buswinka.aipipeline;

import java.io.*;
import java.net.*;
import java.util.*;

public class ClaudeClient implements LLMClient {
    private static final String DEFAULT_ENDPOINT = "https://api.anthropic.com/v1/messages";
    private static final String DEFAULT_MODEL = "claude-sonnet-4-6";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    private String  apiKey           = "";
    private String  model            = DEFAULT_MODEL;
    private String  endpoint         = DEFAULT_ENDPOINT;
    private int     connectTimeoutMs = 10000;
    private int     readTimeoutMs    = 300000; // 5 min — extended thinking can be slow
    private boolean extendedThinking = true;   // can be disabled for non-thinking models

    @Override
    public void configure(Map<String, String> settings) {
        if (settings.containsKey("apiKey"))   apiKey   = settings.get("apiKey");
        if (settings.containsKey("model"))    model    = settings.get("model");
        if (settings.containsKey("endpoint")) endpoint = settings.get("endpoint");
        if (settings.containsKey("timeout")) {
            try { readTimeoutMs = Integer.parseInt(settings.get("timeout")); }
            catch (NumberFormatException ignored) { /* keep current value */ }
        }
        if (settings.containsKey("thinking")) {
            extendedThinking = !"false".equals(settings.get("thinking"));
        }
    }

    @Override
    public String getProviderName() { return "Claude"; }

    @Override
    public boolean isAvailable() { return apiKey != null && !apiKey.isEmpty(); }

    @Override
    public GeneratedPipeline generate(String systemPrompt, List<String[]> history,
                                      String userMessage, ScriptLanguage target) throws Exception {
        String body = buildRequestBody(systemPrompt, history, userMessage);
        String response = post(endpoint, body);
        GeneratedPipeline result = parseResponse(response, target);
        if (result == null) {
            // Single retry with constrained prompt
            String retryUser = userMessage + "\n\nReturn ONLY a JSON object with fields: title, explanation, script. No markdown, no explanation outside the JSON.";
            body = buildRequestBody(systemPrompt, history, retryUser);
            response = post(endpoint, body);
            result = parseResponse(response, target);
        }
        if (result == null) throw new Exception("Claude returned unparseable response after retry");
        return result;
    }

    private String buildRequestBody(String systemPrompt, List<String[]> history, String userMessage) {
        // Claude API: system is a top-level field; messages array contains user/assistant turns only.
        // Extended Thinking: the model reasons in a separate "thinking" content block that is
        // never included in the text response, so no client-side stripping is needed.
        // budget_tokens must be < max_tokens; minimum budget is 1024.
        StringBuilder sb = new StringBuilder();
        sb.append("{");
        sb.append("\"model\":\"").append(JsonHelper.escape(model)).append("\",");
        if (extendedThinking) {
            sb.append("\"max_tokens\":16000,");
            sb.append("\"thinking\":{\"type\":\"enabled\",\"budget_tokens\":10000},");
        } else {
            sb.append("\"max_tokens\":4096,");
        }
        sb.append("\"system\":\"").append(JsonHelper.escape(systemPrompt)).append("\",");
        sb.append("\"messages\":[");
        // history entries — user turns as plain strings, assistant turns as content arrays.
        // Content arrays are the spec-correct format for extended thinking multi-turn.
        if (history != null) {
            for (String[] msg : history) {
                if ("user".equals(msg[0])) {
                    sb.append("{\"role\":\"user\",\"content\":\"")
                      .append(JsonHelper.escape(msg[1])).append("\"},");
                } else {
                    sb.append("{\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"")
                      .append(JsonHelper.escape(msg[1])).append("\"}]},");
                }
            }
        }
        sb.append("{\"role\":\"user\",\"content\":\"").append(JsonHelper.escape(userMessage)).append("\"}");
        sb.append("]}");
        return sb.toString();
    }

    private String post(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(connectTimeoutMs);
            conn.setReadTimeout(readTimeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("x-api-key", apiKey);
            conn.setRequestProperty("anthropic-version", ANTHROPIC_VERSION);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(body.getBytes("UTF-8"));
            }
            int code = conn.getResponseCode();
            InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
            StringBuilder resp = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"))) {
                String line;
                while ((line = br.readLine()) != null) resp.append(line).append("\n");
            }
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + ": " + resp);
            return resp.toString();
        } finally {
            conn.disconnect();
        }
    }

    private GeneratedPipeline parseResponse(String responseJson, ScriptLanguage target) {
        // With native extended thinking the response contains multiple content blocks:
        //   {"content": [{"type":"thinking","thinking":"..."}, {"type":"text","text":"..."}]}
        // We need to find the block where "type":"text" and extract its "text" value,
        // skipping any "type":"thinking" blocks.
        String content = extractTextContent(responseJson);
        if (content == null) return null;
        content = JsonHelper.stripCodeFences(content);
        String title  = JsonHelper.extractField(content, "title");
        String expl   = JsonHelper.extractField(content, "explanation");
        String script = JsonHelper.extractField(content, "script");
        if (title != null && script != null) {
            ScriptLanguage lang = parseLanguageField(content, target);
            return new GeneratedPipeline(title, expl != null ? expl : "", script, lang);
        }
        // If it looks like a failed JSON attempt, return null to trigger a retry
        if (content.contains("\"script\"") || content.contains("\"title\"")) return null;
        // Otherwise treat it as a plain conversational reply
        return GeneratedPipeline.conversational(content.trim());
    }

    /**
     * Finds the content block with "type":"text" in the Claude API response and
     * returns the value of its "text" field. Skips "type":"thinking" blocks.
     */
    private static String extractTextContent(String json) {
        int searchFrom = 0;
        while (true) {
            int typeIdx = json.indexOf("\"type\"", searchFrom);
            if (typeIdx < 0) return null;
            int colonIdx = json.indexOf(':', typeIdx + 6);
            if (colonIdx < 0) return null;
            // Find the quoted value after the colon
            int valStart = -1;
            for (int i = colonIdx + 1; i < json.length(); i++) {
                if (json.charAt(i) == '"') { valStart = i + 1; break; }
                if (!Character.isWhitespace(json.charAt(i))) break;
            }
            if (valStart < 0) { searchFrom = colonIdx + 1; continue; }
            int valEnd = json.indexOf('"', valStart);
            if (valEnd < 0) { searchFrom = valStart; continue; }
            String typeVal = json.substring(valStart, valEnd);
            if ("text".equals(typeVal)) {
                // Found a text block — extract its "text" field value
                return JsonHelper.extractField(json.substring(typeIdx), "text");
            }
            searchFrom = valEnd + 1;
        }
    }

    private static ScriptLanguage parseLanguageField(String json, ScriptLanguage fallback) {
        String lang = JsonHelper.extractField(json, "language");
        if ("python".equalsIgnoreCase(lang)) return ScriptLanguage.PYTHON;
        if ("ijm".equalsIgnoreCase(lang))    return ScriptLanguage.IJM;
        return fallback;
    }
}
