import com.copilot.Config;
import com.copilot.Llm;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

// Standalone sample: one request to the Paytm inference gateway.
// Run: mvn -q compile exec:java ... or: java -cp target/meeting-copilot.jar examples/ApiSample.java "your question"
public class ApiSample {
    public static void main(String[] args) throws Exception {
        String q = args.length == 0 ? "Say hello in one line." : String.join(" ", args);
        JsonArray messages = new JsonArray();
        messages.add(msg("system", "You are a helpful assistant."));
        messages.add(msg("user", q));
        System.out.println(Llm.chat(Config.load(), messages));
    }

    private static JsonObject msg(String role, String content) {
        JsonObject m = new JsonObject();
        m.addProperty("role", role);
        m.addProperty("content", content);
        return m;
    }
}
