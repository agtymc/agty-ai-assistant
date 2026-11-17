package org.agty.aiassistant.help;

import com.intellij.openapi.components.Service;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.LightVirtualFile;
import java.util.function.Consumer;

@Service(Service.Level.PROJECT)
public final class CommandHelpService {
    static final class HelpFile extends LightVirtualFile {
        HelpFile() { super("Команды Codex — руководство и примеры"); setWritable(false); }
    }
    private final Project project;
    private final HelpFile file = new HelpFile();
    private Consumer<String> insertion;
    private String selected = "";
    private org.agty.aiassistant.chat.CommandAvailability.Context context = new org.agty.aiassistant.chat.CommandAvailability.Context("codex", true, false, false, false, null, "");
    public void updateContext(org.agty.aiassistant.chat.CommandAvailability.Context context) { if (context.equals(this.context)) return;
        this.context = context;
        for (var editor : FileEditorManager.getInstance(project).getAllEditors(file))
            if (editor instanceof CommandHelpEditor help) help.refreshAvailability();
    }
    org.agty.aiassistant.chat.CommandAvailability.Context context() { return context; }
    public CommandHelpService(Project project) { this.project = project; }
    public void open(String command, Consumer<String> insertion) {
        this.insertion = insertion; selected = command;
        var manager = FileEditorManager.getInstance(project);
        com.intellij.openapi.application.WriteIntentReadAction.run((Runnable) () -> manager.openFile(file, true));
        for (var editor : manager.getAllEditors(file))
            if (editor instanceof CommandHelpEditor help) help.select(command);
    }
    String selected() { return selected; }
    void insert(String text) { if (insertion != null) insertion.accept(text); }
}
