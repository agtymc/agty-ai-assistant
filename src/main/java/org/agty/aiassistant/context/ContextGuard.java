package org.agty.aiassistant.context;

import org.agty.aiassistant.chat.*;
import org.agty.aiassistant.security.ProjectAccessPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

/** Recheck exclusions when a queued request actually starts, before passing its frozen prompt to a provider. */
public final class ContextGuard {
    public static ChatProvider.Request protect(ChatProvider.Request request, List<ContextItem> context) {
        List<ContextItem> sources = List.copyOf(context);
        return new ChatProvider.Request() {
            public void run(Path directory, String prompt, Consumer<ChatEvent> events) throws Exception {
                var checked = ProjectContextCollector.validated(directory, new ProjectAccessPolicy(directory), sources);
                if (checked.items().size() != sources.size())
                    throw new IllegalStateException("Контекст изменил доступность во время ожидания. Проверьте список и отправьте запрос заново. " + String.join(" ", checked.notes()));
                request.run(directory, prompt, events);
            }
            public void cancel() { request.cancel(); }
            public void respond(String id, com.google.gson.JsonObject response) throws Exception { request.respond(id, response); }
            public void steer(String prompt) throws Exception { request.steer(prompt); }
        };
    }
    private ContextGuard() { }
}
