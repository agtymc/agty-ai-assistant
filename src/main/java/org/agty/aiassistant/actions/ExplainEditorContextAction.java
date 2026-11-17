package org.agty.aiassistant.actions;

public final class ExplainEditorContextAction extends EditorContextAction {
    @Override protected String prompt() {
        return EditorPromptTemplates.prompt(EditorPromptTemplates.Kind.EXPLAIN);
    }
}
