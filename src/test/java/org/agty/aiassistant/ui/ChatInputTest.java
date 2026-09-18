package org.agty.aiassistant.ui;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ChatInputTest {
    @Test void enterSendsAndShiftEnterInsertsNewline() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            JTextArea input = new JTextArea("hello");
            AtomicInteger sends = new AtomicInteger();
            ChatInput.configure(input, sends::incrementAndGet);
            input.setCaretPosition(input.getText().length());
            invoke(input, "shift ENTER");
            assertEquals("hello\n", input.getText());
            assertEquals(0, sends.get());
            invoke(input, "ENTER");
            assertEquals(1, sends.get());
            assertEquals("hello\n", input.getText());
        });
    }
    private void invoke(JTextArea input, String key) {
        Object action = input.getInputMap().get(KeyStroke.getKeyStroke(key));
        input.getActionMap().get(action).actionPerformed(new ActionEvent(input, 0, "test"));
    }
}
