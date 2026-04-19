package buswinka.aipipeline;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

public class MockLLM implements LLMClient {
    private static final String[][] MACROS = {
        {"Threshold & Count", "Applies Otsu threshold and counts particles.",
         "run(\"Duplicate...\", \"title=working\");\nrun(\"8-bit\");\nsetAutoThreshold(\"Otsu dark\");\nrun(\"Analyze Particles...\", \"size=10-Infinity show=Outlines display\");"},
        {"Measure Fluorescence", "Measures mean intensity in each ROI.",
         "run(\"Set Measurements...\", \"area mean standard min\");\nrun(\"Measure\");"},
        {"Z-Projection", "Maximum intensity projection across Z-stack.",
         "run(\"Z Project...\", \"projection=[Max Intensity]\");"},
        {"Gaussian Blur", "Applies Gaussian blur with sigma=2.",
         "run(\"Duplicate...\", \"title=blurred\");\nrun(\"Gaussian Blur...\", \"sigma=2\");"},
        {"Split Channels", "Splits a multi-channel image into separate windows.",
         "run(\"Split Channels\");"}
    };

    private final AtomicInteger index = new AtomicInteger(0);

    @Override
    public void configure(Map<String, String> settings) { /* no-op for mock */ }

    @Override
    public GeneratedPipeline generate(String systemPrompt, List<String[]> history,
                                      String userMessage, ScriptLanguage target) throws Exception {
        String[] macro = MACROS[index.getAndIncrement() % MACROS.length];
        return new GeneratedPipeline(macro[0], macro[1], macro[2], ScriptLanguage.IJM);
    }

    @Override
    public String getProviderName() { return "AI"; }

    @Override
    public boolean isAvailable() { return true; }
}
