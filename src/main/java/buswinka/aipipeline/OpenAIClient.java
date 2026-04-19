package buswinka.aipipeline;

import java.io.*;
import java.net.*;
import java.util.*;

public class OpenAIClient implements LLMClient {
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1/chat/completions";
    private static final String DEFAULT_MODEL = "gpt-4o";

    private String apiKey = "";
    private String model = DEFAULT_MODEL;
    private String endpoint = DEFAULT_ENDPOINT;
    private int timeoutMs = 60000;

    @Override
    public void configure(Map<String, String> settings) {
        if (settings.containsKey("apiKey"))   apiKey   = settings.get("apiKey");
        if (settings.containsKey("model"))    model    = settings.get("model");
        if (settings.containsKey("endpoint")) endpoint = settings.get("endpoint");
        if (settings.containsKey("timeout")) {
            try { timeoutMs = Integer.parseInt(settings.get("timeout")); }
            catch (NumberFormatException ignored) { /* keep current value */ }
        }
    }

    @Override
    public String getProviderName() { return "ChatGPT"; }

    @Override
    public boolean isAvailable() { return apiKey != null && !apiKey.isEmpty(); }

    @Override
    public GeneratedPipeline generate(String systemPrompt, List<String[]> history,
                                      String userMessage, ScriptLanguage target) throws Exception {
        String body = buildRequestBody(systemPrompt, history, userMessage);
        String response = post(endpoint, body);
        GeneratedPipeline result = parseResponse(response, target);
        if (result == null) {
            String retryUser = userMessage + "\n\nReturn ONLY a JSON object with fields: title, explanation, script. No markdown, no explanation outside the JSON.";
            body = buildRequestBody(systemPrompt, history, retryUser);
            response = post(endpoint, body);
            result = parseResponse(response, target);
        }
        if (result == null) throw new Exception("OpenAI returned unparseable response after retry");
        return result;
    }

    private String buildRequestBody(String systemPrompt, List<String[]> history, String userMessage) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"model\":\"").append(JsonHelper.escape(model)).append("\",");
        sb.append("\"messages\":[");
        // System message first
        sb.append("{\"role\":\"system\",\"content\":\"").append(JsonHelper.escape(systemPrompt)).append("\"}");
        if (history != null) {
            for (String[] msg : history) {
                String role = "user".equals(msg[0]) ? "user" : "assistant";
                sb.append(",{\"role\":\"").append(role).append("\",\"content\":\"")
                  .append(JsonHelper.escape(msg[1])).append("\"}");
            }
        }
        sb.append(",{\"role\":\"user\",\"content\":\"").append(JsonHelper.escape(userMessage)).append("\"}");
        sb.append("]}");
        return sb.toString();
    }

    private String post(String urlStr, String body) throws Exception {
        URL url = new URL(urlStr);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        try {
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
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
        // OpenAI: {"choices": [{"message": {"content": "..."}}]}
        // Extract content from choices[0].message.content
        String content = JsonHelper.extractField(responseJson, "content");
        if (content == null) return null;
        content = JsonHelper.stripThinkingBlock(content);
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

    private static ScriptLanguage parseLanguageField(String json, ScriptLanguage fallback) {
        String lang = JsonHelper.extractField(json, "language");
        if ("python".equalsIgnoreCase(lang)) return ScriptLanguage.PYTHON;
        if ("ijm".equalsIgnoreCase(lang))    return ScriptLanguage.IJM;
        return fallback;
    }
}
