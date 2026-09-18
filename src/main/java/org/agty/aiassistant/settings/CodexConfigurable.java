package org.agty.aiassistant.settings;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.util.ui.FormBuilder;
import org.agty.aiassistant.core.CodexCommand;
import org.agty.aiassistant.core.CodexProcess;

import javax.swing.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

public final class CodexConfigurable implements Configurable {
    private JPanel panel;
    private TextFieldWithBrowseButton path;
    private JButton check;
    private JLabel result;
    private CodexProcess probe;

    @Override public String getDisplayName() { return "AGTY AI Assistant"; }

    @Override public JComponent createComponent() {
        path = new TextFieldWithBrowseButton();
        path.addBrowseFolderListener(null, FileChooserDescriptorFactory.createSingleFileDescriptor()
                .withTitle("Выберите исполняемый файл Codex"));
        check = new JButton("Проверить Codex");
        result = new JLabel("Проверка запускает только codex --version.");
        check.addActionListener(e -> probe());
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Путь к бинарнику Codex:", path)
                .addComponent(new JLabel("Абсолютный путь без кавычек или codex для поиска в PATH IDE."))
                .addComponent(check).addComponent(result)
                .addComponent(new JLabel("Для входа выполните codex login в терминале под тем же пользователем."))
                .addComponent(new JLabel("Плагин использует авторизацию и настройки установленного Codex CLI."))
                .addComponentFillVertically(new JPanel(), 0).getPanel();
        reset();
        return panel;
    }

    private void probe() {
        final String binary;
        try { binary = CodexCommand.executable(path.getText()); }
        catch (RuntimeException e) { result.setText(e.getMessage()); return; }
        CodexProcess run = new CodexProcess();
        probe = run;
        check.setEnabled(false);
        result.setText("Проверка…");
        // Capture on EDT: the completion must run while Settings is still modal.
        ModalityState modality = ModalityState.current();
        ApplicationManager.getApplication().executeOnPooledThread(() -> {
            StringBuilder output = new StringBuilder();
            StringBuilder errors = new StringBuilder();
            String message;
            try {
                int code = run.run(List.of(binary, "--version"), Path.of(System.getProperty("user.home")),
                        "", Duration.ofSeconds(10), line -> {
                            if (output.length() < 1000) output.append(line, 0, Math.min(line.length(), 1000)).append(' ');
                        }, line -> {
                            if (errors.length() < 1000) errors.append(line, 0, Math.min(line.length(), 1000)).append(' ');
                        });
                message = code == 0 && !output.isEmpty() ? output.toString()
                        : code == 0 && !errors.isEmpty() ? errors.toString()
                        : "Codex завершился с кодом " + code + (errors.isEmpty() ? " (нет вывода)" : ": " + errors);
            } catch (Exception e) { message = "Не удалось запустить Codex: " + e.getMessage(); }
            String text = message;
            ApplicationManager.getApplication().invokeLater(() -> {
                if (panel != null && probe == run) {
                    result.setText(text);
                    check.setEnabled(true);
                    probe = null;
                }
            }, modality);
        });
    }

    @Override public boolean isModified() {
        return path != null && !path.getText().trim().equals(CodexSettings.getInstance().getExecutablePath());
    }
    @Override public void apply() throws ConfigurationException {
        try {
            CodexSettings.getInstance().setExecutablePath(CodexCommand.executable(path.getText()));
        } catch (RuntimeException e) { throw new ConfigurationException(e.getMessage()); }
    }
    @Override public void reset() {
        if (path != null) path.setText(CodexSettings.getInstance().getExecutablePath());
    }
    @Override public void disposeUIResources() {
        if (probe != null) probe.cancel();
        probe = null;
        panel = null;
        path = null;
        check = null;
        result = null;
    }
}
