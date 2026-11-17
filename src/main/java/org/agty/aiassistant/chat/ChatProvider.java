package org.agty.aiassistant.chat;
import java.nio.file.Path;
import java.util.function.Consumer;

public interface ChatProvider {
    String name();
    Request newRequest();
    default Request newRequest(RequestOptions options) {
        if (options.mode() != RequestOptions.Mode.WORK)
            throw new IllegalStateException("Провайдер пока не поддерживает режим Plan.");
        if (!options.attachments().isEmpty())
            throw new IllegalStateException("Передайте текстовый контекст в prompt; типизированные вложения пока не поддержаны.");
        return newRequest(options.sessionId(), options.access() != RequestOptions.Access.READ_ONLY,
                options.model(), options.effort(), options.fork(), options.access() == RequestOptions.Access.FULL);
    }
    default Request newRequest(String sessionId) { return newRequest(); }
    default Request newRequest(String sessionId, boolean writeAccess) {
        if (writeAccess) throw new IllegalStateException("Этот провайдер пока не поддерживает режим изменения файлов.");
        return newRequest(sessionId);
    }
    default Request newRequest(String sessionId, boolean writeAccess, String model) {
        if (!model.isBlank()) throw new IllegalStateException("Этот провайдер пока не поддерживает выбор модели.");
        return newRequest(sessionId, writeAccess);
    }
    default Request newRequest(String sessionId, boolean writeAccess, String model, String effort) {
        if (!effort.isBlank()) throw new IllegalStateException("Этот провайдер пока не поддерживает настройку рассуждения.");
        return newRequest(sessionId, writeAccess, model);
    }
    default Request newRequest(String sessionId, boolean writeAccess, String model, String effort, boolean forkForModel) {
        if (forkForModel) throw new IllegalStateException("Этот провайдер не поддерживает переключение модели с сохранением истории.");
        return newRequest(sessionId, writeAccess, model, effort);
    }
    default Request newRequest(String sessionId, boolean writeAccess, String model, String effort,
                               boolean forkForModel, boolean unrestrictedAccess) {
        if (unrestrictedAccess) throw new IllegalStateException("Этот провайдер не поддерживает режим без ограничений.");
        return newRequest(sessionId, writeAccess, model, effort, forkForModel);
    }
    interface Request {
        void run(Path directory, String prompt, Consumer<ChatEvent> events) throws Exception;
        void cancel();
        default void respond(String id, com.google.gson.JsonObject response) throws Exception {
            throw new IllegalStateException("Провайдер не поддерживает ответы на запросы сервера.");
        }
    }
}
