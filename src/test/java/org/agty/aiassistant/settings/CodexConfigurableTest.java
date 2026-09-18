package org.agty.aiassistant.settings;

import com.intellij.openapi.application.impl.LaterInvocator;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.testFramework.PlatformTestUtil;
import com.intellij.testFramework.TestApplicationManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.*;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class CodexConfigurableTest {
    @TempDir Path directory;

    @BeforeAll
    static void initializeApplication() {
        // Start platform services before the per-test thread-leak baseline is captured.
        TestApplicationManager.getInstance();
        PlatformTestUtil.waitForAllBackgroundActivityToCalmDown();
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void versionCheckCompletesInsideModalSettings() throws Exception {
        Path wrapper = directory.resolve("codexp wrapper");
        Files.writeString(wrapper, "#!/bin/sh\n[ \"$1\" = \"--version\" ] || exit 7\nprintf 'codex-cli test-version\\n'\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        checkWhileModal(wrapper, "codex-cli test-version");
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void failedCheckDisplaysStderrInsideModalSettings() throws Exception {
        Path wrapper = directory.resolve("broken wrapper");
        Files.writeString(wrapper, "#!/bin/sh\nprintf 'wrapper failure\\n' >&2\nexit 7\n");
        assertTrue(wrapper.toFile().setExecutable(true));
        checkWhileModal(wrapper, "wrapper failure");
    }

    private void checkWhileModal(Path wrapper, String expected) throws Exception {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Object modal = new Object();
            CodexConfigurable settings = new CodexConfigurable();
            LaterInvocator.enterModal(modal);
            try {
                List<Component> components = descendants(settings.createComponent());
                TextFieldWithBrowseButton path = components.stream()
                        .filter(TextFieldWithBrowseButton.class::isInstance)
                        .map(TextFieldWithBrowseButton.class::cast).findFirst().orElseThrow();
                JButton check = components.stream().filter(JButton.class::isInstance)
                        .map(JButton.class::cast).filter(b -> "Проверить Codex".equals(b.getText()))
                        .findFirst().orElseThrow();
                path.setText(wrapper.toString());
                check.doClick();
                assertFalse(check.isEnabled());
                PlatformTestUtil.waitWithEventsDispatching("Version result was blocked by modality", check::isEnabled, 15);
                assertTrue(LaterInvocator.isInModalContext());
                assertTrue(components.stream().filter(JLabel.class::isInstance).map(JLabel.class::cast)
                        .anyMatch(label -> label.getText() != null && label.getText().contains(expected)));
            } catch (Throwable e) {
                failure.set(e);
            } finally {
                settings.disposeUIResources();
                LaterInvocator.leaveModal(modal);
            }
        });
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static List<Component> descendants(Component parent) {
        List<Component> result = new ArrayList<>();
        result.add(parent);
        if (parent instanceof Container container) {
            for (Component child : container.getComponents()) result.addAll(descendants(child));
        }
        return result;
    }
}
