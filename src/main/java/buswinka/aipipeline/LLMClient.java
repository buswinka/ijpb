package buswinka.aipipeline;
import java.util.List;
import java.util.Map;

public interface LLMClient {
    /** Configure backend (apiKey, model, endpoint, temperature). */
    void configure(Map<String, String> settings);

    /** Generate a pipeline given conversation history and user message. */
    GeneratedPipeline generate(String systemPrompt, List<String[]> history,
                               String userMessage, ScriptLanguage target) throws Exception;

    /** Returns true if configured and reachable. */
    boolean isAvailable();

    /** Human-readable provider name shown in the chat UI (e.g. "Ollama", "Claude"). */
    String getProviderName();
}
