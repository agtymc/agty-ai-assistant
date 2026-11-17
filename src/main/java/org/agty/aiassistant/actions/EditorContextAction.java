package org.agty.aiassistant.actions;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import org.agty.aiassistant.context.CollectedContext;
import org.agty.aiassistant.context.ProjectContextCollector;
import org.agty.aiassistant.security.ProjectAccessPolicy;
import org.agty.aiassistant.ui.AiAssistantChatPanel;
import org.jetbrains.annotations.NotNull;

import java.nio.file.Path;

abstract class EditorContextAction extends AnAction {
    private static final String TOOL_WINDOW_ID = "AGTY AI Assistant";

    @Override public void update(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        event.getPresentation().setEnabled(project != null && editor != null && project.getBasePath() != null);
    }

    @Override public void actionPerformed(@NotNull AnActionEvent event) {
        Project project = event.getProject();
        Editor editor = event.getData(CommonDataKeys.EDITOR);
        if (project == null || editor == null || project.getBasePath() == null) return;
        Path root = Path.of(project.getBasePath());
        ProjectAccessPolicy policy;
        try { policy = new ProjectAccessPolicy(root); }
        catch (RuntimeException e) { Messages.showWarningDialog(project, e.getMessage(), TOOL_WINDOW_ID); return; }
        CollectedContext context = ProjectContextCollector.editor(project, root, policy, editor);
        if (context.items().isEmpty()) {
            Messages.showWarningDialog(project, context.notes().isEmpty() ? "Контекст редактора не найден." : context.notes().getFirst(), TOOL_WINDOW_ID);
            return;
        }
        ToolWindow toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID);
        if (toolWindow == null) {
            Messages.showWarningDialog(project, "Tool Window AGTY AI Assistant недоступен.", TOOL_WINDOW_ID);
            return;
        }
        toolWindow.activate(() -> submit(toolWindow, project, prompt(), context));
    }

    private static void submit(ToolWindow toolWindow, Project project, String prompt, CollectedContext context) {
        var content = toolWindow.getContentManager().getContent(0);
        if (content == null || !(content.getComponent() instanceof AiAssistantChatPanel panel)) {
            Messages.showWarningDialog(project, "Чат AGTY AI Assistant ещё не готов.", TOOL_WINDOW_ID);
            return;
        }
        panel.submitEditorAction(prompt, context);
    }

    protected abstract String prompt();
}
