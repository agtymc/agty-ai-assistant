package org.agty.aiassistant.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;

@State(name = "AgtyAiAssistantCodexSettings", storages = @Storage("agty-ai-assistant-codex.xml"))
@Service(Service.Level.APP)
public final class CodexSettings implements PersistentStateComponent<CodexSettings.Data> {
    public static final class Data {
        public String executablePath = "codex";
    }

    private Data data = new Data();

    public static CodexSettings getInstance() {
        return ApplicationManager.getApplication().getService(CodexSettings.class);
    }

    @Override public @NotNull Data getState() { return data; }
    @Override public void loadState(@NotNull Data state) { data = state; }
    public String getExecutablePath() { return data.executablePath; }
    public void setExecutablePath(String value) { data.executablePath = value; }
}
