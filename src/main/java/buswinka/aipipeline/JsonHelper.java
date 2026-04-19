package buswinka.aipipeline;

/**
 * Lightweight JSON utilities — no external deps, Java 8 compatible.
 * Handles common LLM response patterns: code-fenced JSON, escaped strings.
 */
public class JsonHelper {

    /**
     * Removes {@code <think>...</think>} reasoning blocks that the LLM emits before
     * its actual response. The block is stripped silently and never shown to the user.
     */
    public static String stripThinkingBlock(String text) {
        if (text == null) return null;
        return text.replaceAll("(?si)<think(?:ing)?>.*?</think(?:ing)?>\\s*", "").trim();
    }

    /** Strip markdown code fences LLMs frequently wrap around JSON. */
    public static String stripCodeFences(String text) {
        if (text == null) return null;
        String trimmed = text.trim();
        int fenceStart = trimmed.indexOf("```");
        if (fenceStart < 0) return trimmed;
        int fenceEnd = trimmed.lastIndexOf("```");
        if (fenceEnd <= fenceStart) return trimmed;
        // If there is another ``` between the first and last markers, the response
        // contains multiple code blocks (conversational markdown) — leave it untouched.
        if (trimmed.indexOf("```", fenceStart + 3) != fenceEnd) return trimmed;
        int contentStart = trimmed.indexOf('\n', fenceStart);
        if (contentStart < 0) return trimmed;
        return trimmed.substring(contentStart + 1, fenceEnd).trim();
    }

    /**
     * Extract a string field value from a JSON object. Handles escape sequences.
     * Returns the value of the LAST occurrence of the key so that if the LLM emits
     * a duplicate key, we prefer the most recent (often the corrected) value.
     */
    public static String extractField(String json, String key) {
        if (json == null || key == null) return null;
        String search = "\"" + key + "\"";

        // Find the last occurrence of the key
        int lastIdx = -1;
        int searchFrom = 0;
        while (true) {
            int found = json.indexOf(search, searchFrom);
            if (found < 0) break;
            lastIdx = found;
            searchFrom = found + search.length();
        }
        int idx = lastIdx;
        if (idx < 0) return null;

        int colon = json.indexOf(':', idx + search.length());
        if (colon < 0) return null;
        int start = -1;
        for (int i = colon + 1; i < json.length(); i++) {
            if (json.charAt(i) == '"') { start = i; break; }
            if (!Character.isWhitespace(json.charAt(i))) return null;
        }
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\' && i + 1 < json.length()) {
                char next = json.charAt(++i);
                switch (next) {
                    case '"':  sb.append('"');  break;
                    case '\\': sb.append('\\'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            try {
                                sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                                i += 4;
                            } catch (NumberFormatException e) {
                                sb.append('\\').append(next);
                            }
                        } else {
                            sb.append('\\').append(next);
                        }
                        break;
                    default:   sb.append('\\').append(next); break;
                }
                continue;
            }
            if (c == '"') break;
            sb.append(c);
        }
        return sb.toString();
    }

    /** Escape a string for embedding in a JSON value. */
    public static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    /**
     * Extract a numeric (integer) field value from a JSON object.
     * Returns the value of the LAST occurrence of the key, or -1 if not found
     * or the value is not a non-negative integer.
     */
    public static int extractIntField(String json, String key) {
        if (json == null || key == null) return -1;
        String search = "\"" + key + "\"";
        int lastIdx = -1;
        int searchFrom = 0;
        while (true) {
            int found = json.indexOf(search, searchFrom);
            if (found < 0) break;
            lastIdx = found;
            searchFrom = found + search.length();
        }
        if (lastIdx < 0) return -1;
        int colon = json.indexOf(':', lastIdx + search.length());
        if (colon < 0) return -1;
        StringBuilder digits = new StringBuilder();
        for (int i = colon + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if (Character.isDigit(c) || (c == '-' && digits.length() == 0)) {
                digits.append(c);
                while (++i < json.length() && Character.isDigit(json.charAt(i))) {
                    digits.append(json.charAt(i));
                }
                break;
            }
            break; // non-numeric value
        }
        if (digits.length() == 0) return -1;
        try { return Integer.parseInt(digits.toString()); }
        catch (NumberFormatException e) { return -1; }
    }

    /** Build a JSON messages array from conversation history + new user message. */
    public static String buildMessagesArray(java.util.List<String[]> history, String userMessage) {
        StringBuilder messages = new StringBuilder("[");
        if (history != null) {
            for (String[] msg : history) {
                messages.append(String.format("{\"role\":\"%s\",\"content\":\"%s\"},",
                    escape(msg[0]), escape(msg[1])));
            }
        }
        messages.append(String.format("{\"role\":\"user\",\"content\":\"%s\"}", escape(userMessage)));
        messages.append("]");
        return messages.toString();
    }
}
