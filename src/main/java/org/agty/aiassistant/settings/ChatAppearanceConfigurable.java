package org.agty.aiassistant.settings;

import com.google.gson.Gson;
import com.intellij.openapi.options.*;
import com.intellij.util.ui.FormBuilder;
import javax.swing.*;
import java.awt.*;

public final class ChatAppearanceConfigurable implements Configurable {
    private final JComboBox<String> font = new JComboBox<>(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    private final JComboBox<String> codeFont = new JComboBox<>(GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
    private final JSpinner fontSize = number(14, 10, 28), activitySize = number(14, 10, 28), codeSize = number(13, 10, 28), spacing = number(18, 4, 40), rows = number(2, 2, 12);
    private final JCheckBox ideColors = new JCheckBox("Использовать цвета темы IDE"), wrap = new JCheckBox("Переносить длинные строки кода"),
            autoScroll = new JCheckBox("Прокручивать новые ответы, если открыт конец чата"), activity = new JCheckBox("Показывать ход работы");
    private final JTextField background = new JTextField(9), foreground = new JTextField(9), userBackground = new JTextField(9), accent = new JTextField(9);
    private static JSpinner number(int value, int min, int max) { return new JSpinner(new SpinnerNumberModel(value, min, max, 1)); }
    @Override public String getDisplayName() { return "Оформление чата"; }
    @Override public JComponent createComponent() {
        font.setEditable(true); codeFont.setEditable(true);
        JButton defaults = new JButton("Сбросить оформление");
        defaults.addActionListener(e -> display(new ChatAppearance.Data()));
        var panel = FormBuilder.createFormBuilder()
                .addLabeledComponent("Шрифт сообщений и ввода:", font).addLabeledComponent("Размер текста:", fontSize)
                .addLabeledComponent("Размер пояснений и хода работы:", activitySize)
                .addLabeledComponent("Шрифт кода:", codeFont).addLabeledComponent("Размер кода:", codeSize)
                .addLabeledComponent("Расстояние между сообщениями:", spacing).addLabeledComponent("Строк в поле ввода:", rows)
                .addComponent(ideColors).addLabeledComponent("Фон чата:", color(background))
                .addLabeledComponent("Текст:", color(foreground)).addLabeledComponent("Фон сообщения пользователя:", color(userBackground))
                .addLabeledComponent("Ссылки и акценты:", color(accent))
                .addComponent(new JLabel("Свои цвета применяются, когда отключены цвета темы IDE. Подсветка кода — из схемы редактора."))
                .addComponent(wrap).addComponent(autoScroll).addComponent(activity).addComponent(defaults)
                .addComponentFillVertically(new JPanel(), 0).getPanel();
        reset(); return panel;
    }
    private JPanel color(JTextField field) {
        JButton pick = new JButton("Выбрать…");
        pick.addActionListener(e -> {
            Color initial;
            try { initial = Color.decode(field.getText()); } catch (RuntimeException ignored) { initial = Color.GRAY; }
            Color selected = JColorChooser.showDialog(field, "Цвет чата", initial);
            if (selected != null) field.setText(String.format("#%06x", selected.getRGB() & 0xffffff));
        });
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); panel.add(field); panel.add(pick); return panel;
    }
    private ChatAppearance.Data read() {
        var d = new ChatAppearance.Data();
        d.font = String.valueOf(font.getSelectedItem()); d.codeFont = String.valueOf(codeFont.getSelectedItem());
        d.fontSize = (int) fontSize.getValue(); d.activitySize = (int) activitySize.getValue(); d.codeSize = (int) codeSize.getValue();
        d.spacing = (int) spacing.getValue(); d.inputRows = (int) rows.getValue();
        d.ideColors = ideColors.isSelected(); d.wrapCode = wrap.isSelected(); d.autoScroll = autoScroll.isSelected(); d.showActivity = activity.isSelected();
        d.background = background.getText().trim(); d.foreground = foreground.getText().trim();
        d.userBackground = userBackground.getText().trim(); d.accent = accent.getText().trim(); return d;
    }
    private void display(ChatAppearance.Data d) {
        font.setSelectedItem(d.font); codeFont.setSelectedItem(d.codeFont); fontSize.setValue(d.fontSize); activitySize.setValue(d.activitySize); codeSize.setValue(d.codeSize);
        spacing.setValue(d.spacing); rows.setValue(d.inputRows); ideColors.setSelected(d.ideColors); wrap.setSelected(d.wrapCode);
        autoScroll.setSelected(d.autoScroll); activity.setSelected(d.showActivity); background.setText(d.background);
        foreground.setText(d.foreground); userBackground.setText(d.userBackground); accent.setText(d.accent);
    }
    @Override public boolean isModified() { return !new Gson().toJson(read()).equals(new Gson().toJson(ChatAppearance.getInstance().getState())); }
    @Override public void reset() { display(ChatAppearance.getInstance().getState()); }
    @Override public void apply() throws ConfigurationException {
        var d = read();
        for (String value : new String[]{d.background, d.foreground, d.userBackground, d.accent})
            if (!value.matches("#[0-9a-fA-F]{6}")) throw new ConfigurationException("Укажите цвет в формате #RRGGBB.");
        if (d.font.isBlank() || d.codeFont.isBlank()) throw new ConfigurationException("Укажите названия шрифтов.");
        ChatAppearance.getInstance().update(d);
    }
}
