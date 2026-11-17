package org.agty.aiassistant.actions;

public final class FindProblemsEditorContextAction extends EditorContextAction {
    @Override protected String prompt() {
        return EditorPromptTemplates.prompt(EditorPromptTemplates.Kind.FIND_PROBLEMS);
    }
}
