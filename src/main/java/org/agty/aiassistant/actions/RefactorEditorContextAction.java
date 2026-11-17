package org.agty.aiassistant.actions;

public final class RefactorEditorContextAction extends EditorContextAction {
    @Override protected String prompt() {
        return EditorPromptTemplates.prompt(EditorPromptTemplates.Kind.REFACTOR);
    }
}
