package org.agty.aiassistant.ui;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import java.awt.event.ActionEvent;

public final class ChatInput {
    private ChatInput() {}
    public static void configure(JTextArea input, Runnable send) {
        input.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send-message");
        input.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "send-message");
        input.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), DefaultEditorKit.insertBreakAction);
        input.getActionMap().put("send-message", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { send.run(); }
        });
        input.setToolTipText("Enter — отправить; Shift+Enter — новая строка");
    }
}
