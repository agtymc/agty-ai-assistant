package org.agty.aiassistant.help;

import com.intellij.openapi.project.ProjectManager;
import com.intellij.testFramework.TestApplicationManager;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class CommandHelpTest {
    @Test void aliasNavigationAndWorkflowRemainAvailableOffline() throws Exception {
        TestApplicationManager.getInstance();
        SwingUtilities.invokeAndWait(() -> {
            var project = ProjectManager.getInstance().getDefaultProject();
            var editor = new CommandHelpEditor(project, new CommandHelpService.HelpFile());
            try {
                editor.select("#cwd");
                assertTrue(text(editor.getComponent()).contains("/pwd"));
                assertTrue(text(editor.getComponent()).contains("Сейчас:"));
                button(editor.getComponent(), "Рабочая последовательность").doClick();
                assertTrue(text(editor.getComponent()).contains("не выполняется автоматически"));
                assertNotNull(button(editor.getComponent(), "Вставить в чат"));
                editor.select("/review");
                assertTrue(text(editor.getComponent()).contains("/review"));
                assertTrue(text(editor.getComponent()).contains("/diff"));
            } finally { editor.dispose(); }
        });
    }
    private static String text(Component component) {
        StringBuilder result = new StringBuilder();
        if (component instanceof JTextArea area) result.append(area.getText());
        if (component instanceof AbstractButton button) result.append(button.getText());
        if (component instanceof Container container) for (Component child : container.getComponents()) result.append(text(child));
        return result.toString();
    }
    private static JButton button(Component component, String title) {
        if (component instanceof JButton button && title.equals(button.getText())) return button;
        if (component instanceof Container container) for (Component child : container.getComponents()) {
            var found = button(child, title); if (found != null) return found;
        }
        return null;
    }
}
