package org.agty.aiassistant.actions;

public final class GenerateCodeEditorContextAction extends EditorContextAction {
    @Override protected String prompt() {
        return EditorPromptTemplates.prompt(EditorPromptTemplates.Kind.GENERATE_CODE);
    }
}
