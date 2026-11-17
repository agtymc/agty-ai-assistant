package org.agty.aiassistant.actions;

public final class SuggestFixEditorContextAction extends EditorContextAction {
    @Override protected String prompt() {
        return EditorPromptTemplates.prompt(EditorPromptTemplates.Kind.SUGGEST_FIX);
    }
}
