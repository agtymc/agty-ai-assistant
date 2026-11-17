package org.agty.aiassistant.help;

import com.intellij.openapi.fileEditor.*;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.ui.components.*;
import org.agty.aiassistant.chat.SlashCommands;
import org.jetbrains.annotations.NotNull;
import javax.swing.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.beans.PropertyChangeListener;

/** Packaged offline help; no browser, account, model or network dependency. */
final class CommandHelpEditor extends UserDataHolderBase implements FileEditor {
    private final JPanel root = new JPanel(new BorderLayout(8, 8));
    private final JTextField search = new JTextField();
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> list = new JList<>(names);
    private final JPanel article = new JPanel();
    private final CommandHelpService service;
    private final VirtualFile file;
    CommandHelpEditor(Project project, VirtualFile file) {
        this.file = file; service = project.getService(CommandHelpService.class);
        search.setToolTipText("Поиск по имени, aliases, описанию, категории и сценариям");
        JPanel navigation = new JPanel(new BorderLayout(4, 0));
        JButton catalog = new JButton("К каталогу"); catalog.addActionListener(e -> search.setText(""));
        navigation.add(catalog, BorderLayout.WEST); navigation.add(search, BorderLayout.CENTER);
        root.add(navigation, BorderLayout.NORTH);
        article.setLayout(new BoxLayout(article, BoxLayout.Y_AXIS));
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, new JBScrollPane(list), new JBScrollPane(article));
        split.setResizeWeight(.25); root.add(split, BorderLayout.CENTER);
        search.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { filter(); }
            public void removeUpdate(DocumentEvent e) { filter(); }
            public void changedUpdate(DocumentEvent e) { filter(); }
        });
        list.addListSelectionListener(e -> { if (!e.getValueIsAdjusting()) render(); });
        filter(); select(service.selected());
    }
    void select(String command) {
        var found = SlashCommands.find(command);
        if (found == null) { search.setText(command); return; }
        search.setText(""); list.setSelectedValue(found.name(), true);
    }
    private void filter() {
        String query = search.getText().strip().toLowerCase(java.util.Locale.ROOT);
        names.clear();
        SlashCommands.ALL.stream().filter(c -> (c.name() + c.aliases() + c.description() + c.scenario() + c.category())
                .toLowerCase(java.util.Locale.ROOT).contains(query)).forEach(c -> names.addElement(c.name()));
        if (!names.isEmpty()) list.setSelectedIndex(0); else { article.removeAll(); article.revalidate(); article.repaint(); }
    }
    private void paragraph(String text) {
        JTextArea area = new JTextArea(text); area.setEditable(false); area.setLineWrap(true); area.setWrapStyleWord(true);
        area.setBackground(root.getBackground()); area.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8)); article.add(area);
    }
    private void render() {
        article.removeAll();
        var command = SlashCommands.find(list.getSelectedValue() == null ? "" : list.getSelectedValue());
        if (command == null) return;
        paragraph(command.name() + " — " + command.description() + "\n" + command.category());
        paragraph(command.purpose() + "\n" + command.scenario());
        paragraph("Синтаксис: " + command.syntax() + "\nAliases: " + String.join(", ", command.aliases()) + "\nUI: " + command.ui());
        for (int index = 0; index < command.examples().size(); index++) {
            String example = command.examples().get(index);
            paragraph(command.exampleContexts().get(index)); paragraph(example);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT));
            JButton copy = new JButton("Копировать"), insert = new JButton("Вставить в чат");
            copy.addActionListener(e -> CopyPasteManager.getInstance().setContents(new StringSelection(example)));
            insert.addActionListener(e -> service.insert(example));
            actions.add(copy); actions.add(insert); article.add(actions);
        }
        paragraph("Результат: " + command.result());
        paragraph("Требования и ограничения: " + command.constraints());
        paragraph("Ошибки: " + command.errors());
        JPanel related = new JPanel(new FlowLayout(FlowLayout.LEFT));
        for (String name : command.related()) {
            JButton link = new JButton(name); link.addActionListener(e -> select(name)); related.add(link);
        }
        article.add(related); article.revalidate(); article.repaint();
    }
    @Override public @NotNull JComponent getComponent() { return root; }
    @Override public JComponent getPreferredFocusedComponent() { return search; }
    @Override public @NotNull String getName() { return "Справочник команд"; }
    @Override public void setState(@NotNull FileEditorState state) { }
    @Override public boolean isModified() { return false; }
    @Override public boolean isValid() { return true; }
    @Override public VirtualFile getFile() { return file; }
    @Override public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) { }
    @Override public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) { }
    @Override public void dispose() { }
}
