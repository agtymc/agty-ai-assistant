package org.agty.aiassistant.help;

import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.*;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.NotNull;

public final class CommandHelpEditorProvider implements FileEditorProvider, DumbAware {
    @Override public boolean accept(@NotNull Project project, @NotNull VirtualFile file) {
        return file instanceof CommandHelpService.HelpFile;
    }
    @Override public @NotNull FileEditor createEditor(@NotNull Project project, @NotNull VirtualFile file) {
        return new CommandHelpEditor(project, file);
    }
    @Override public @NotNull String getEditorTypeId() { return "agty-command-help"; }
    @Override public @NotNull FileEditorPolicy getPolicy() { return FileEditorPolicy.HIDE_DEFAULT_EDITOR; }
}
