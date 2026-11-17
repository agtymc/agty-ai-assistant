package org.agty.aiassistant.ui;

import javax.swing.*;
import java.awt.*;

/** Swing labels interpret an HTML prefix by default, including fetching image URLs. */
public final class PlainText {
    /** Apply before assigning any text supplied by a model, tool, file name or saved session. */
    public static <T extends JComponent> T disableHtml(T component) {
        component.putClientProperty("html.disable", true);
        return component;
    }
    public static DefaultListCellRenderer renderer() { return disableHtml(new DefaultListCellRenderer()); }
    public static JLabel label(String text) { var label = disableHtml(new JLabel()); label.setText(text); return label; }
    public static JMenuItem menuItem(String text) { var item = disableHtml(new JMenuItem()); item.setText(text); return item; }
    public static JCheckBox checkBox(String text, boolean selected) {
        var box = disableHtml(new JCheckBox()); box.setText(text); box.setSelected(selected); return box;
    }

    /** Equivalent to the selection form of JOptionPane.showInputDialog, with inert text. */
    public static Object choose(Component parent, String message, String title, int messageType, Object[] values, Object selected) {
        JComboBox<Object> choices = new JComboBox<>(); choices.setRenderer(renderer());
        for (Object value : values) choices.addItem(value);
        choices.setSelectedItem(selected);
        JTextArea description = new JTextArea(message, 0, 50);
        description.setEditable(false); description.setLineWrap(true); description.setWrapStyleWord(true);
        description.setOpaque(false);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.add(description, BorderLayout.NORTH); content.add(choices, BorderLayout.CENTER);
        return JOptionPane.showConfirmDialog(parent, content, title, JOptionPane.OK_CANCEL_OPTION, messageType) == JOptionPane.OK_OPTION
                ? choices.getSelectedItem() : null;
    }
    private PlainText() { }
}
