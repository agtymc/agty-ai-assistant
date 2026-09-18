package org.agty.aiassistant.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.*;
import org.jetbrains.annotations.NotNull;
import java.util.concurrent.CopyOnWriteArrayList;

@Service(Service.Level.APP)
@State(name = "AgtyChatAppearance", storages = @Storage("agty-chat-appearance.xml"))
public final class ChatAppearance implements PersistentStateComponent<ChatAppearance.Data> {
    public static final class Data {
        public String font = "SansSerif", codeFont = "JetBrains Mono";
        public int fontSize = 14, activitySize = 14, codeSize = 13, spacing = 18, inputRows = 2;
        public boolean ideColors = true, wrapCode = true, autoScroll = true, showActivity = true;
        public String background = "#1e1f22", foreground = "#dfe1e5", userBackground = "#34363c", accent = "#8cb4ff";
    }
    private Data data = new Data();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<>();
    public static ChatAppearance getInstance() { return ApplicationManager.getApplication().getService(ChatAppearance.class); }
    @Override public @NotNull Data getState() { return data; }
    @Override public void loadState(@NotNull Data state) { data = state; }
    public void update(Data state) { data = state; listeners.forEach(Runnable::run); }
    public void addListener(Runnable listener) { listeners.add(listener); }
    public void removeListener(Runnable listener) { listeners.remove(listener); }
}
