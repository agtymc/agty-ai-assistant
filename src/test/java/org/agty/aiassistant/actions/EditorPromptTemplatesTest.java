package org.agty.aiassistant.actions;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class EditorPromptTemplatesTest {
    @Test void changingActionsRequireUnifiedDiffAndReview() {
        for (EditorPromptTemplates.Kind kind : new EditorPromptTemplates.Kind[] {
                EditorPromptTemplates.Kind.SUGGEST_FIX,
                EditorPromptTemplates.Kind.REFACTOR,
                EditorPromptTemplates.Kind.GENERATE_CODE
        }) {
            String prompt = EditorPromptTemplates.prompt(kind);
            assertTrue(prompt.contains("diff --git"));
            assertTrue(prompt.contains("Review"));
            assertTrue(prompt.contains("Не применяй изменения сам"));
        }
    }

    @Test void readOnlyActionsDoNotRequestDiff() {
        assertFalse(EditorPromptTemplates.prompt(EditorPromptTemplates.Kind.EXPLAIN).contains("diff --git"));
        assertFalse(EditorPromptTemplates.prompt(EditorPromptTemplates.Kind.FIND_PROBLEMS).contains("diff --git"));
    }
}
