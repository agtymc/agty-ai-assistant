package org.agty.aiassistant.chat;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.Service;
import org.agty.aiassistant.core.CodexChatProvider;
import org.agty.aiassistant.settings.CodexSettings;
import java.util.*;
import java.util.function.Supplier;

/** Each configured provider/model has a stable id and an independent conversation namespace. */
@Service(Service.Level.APP)
public final class ChatProviderRegistry {
    public record Choice(String id, String label, Supplier<ChatProvider> factory, String model) {
        public Choice(String id, String label, Supplier<ChatProvider> factory) { this(id, label, factory, ""); }
        public Choice {
            if (id == null || id.isBlank() || label == null || label.isBlank())
                throw new IllegalArgumentException("Provider id and label are required");
            Objects.requireNonNull(factory);
            Objects.requireNonNull(model);
        }
        @Override public String toString() { return label; }
    }
    private final Map<String, Choice> choices = new LinkedHashMap<>();
    public ChatProviderRegistry() {
        register(new Choice("codex", "Codex · CLI", () -> new CodexChatProvider(CodexSettings.getInstance().getExecutablePath())));
    }
    public static ChatProviderRegistry getInstance() { return ApplicationManager.getApplication().getService(ChatProviderRegistry.class); }
    public synchronized void register(Choice choice) {
        if (choices.putIfAbsent(choice.id(), choice) != null) throw new IllegalArgumentException("Duplicate provider: " + choice.id());
    }
    public synchronized List<Choice> choices() { return List.copyOf(choices.values()); }
}
