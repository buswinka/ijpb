package buswinka.aipipeline;

import java.util.List;

public class GeneratedPipeline {
    private final String title;
    private final String explanation;
    private final String script;
    private final ScriptLanguage language;
    private final List<String[]> history;

    public GeneratedPipeline(String title, String explanation, String script, ScriptLanguage language) {
        this(title, explanation, script, language, null);
    }

    public GeneratedPipeline(String title, String explanation, String script, ScriptLanguage language,
                             List<String[]> history) {
        this.title = title;
        this.explanation = explanation;
        this.script = script;
        this.language = language;
        this.history = history;
    }

    public String getTitle()       { return title; }
    public String getExplanation() { return explanation; }
    public String getScript()      { return script; }
    public ScriptLanguage getLanguage() { return language; }

    /** Conversation history restored from disk, or null if not loaded from a saved file. */
    public List<String[]> getHistory() { return history; }

    /** True when this response is conversational text rather than a generated script. */
    public boolean isConversational() { return script == null; }

    /** Creates a conversational (non-script) response carrying only a plain-text message. */
    public static GeneratedPipeline conversational(String message) {
        return new GeneratedPipeline(null, message, null, null);
    }
}