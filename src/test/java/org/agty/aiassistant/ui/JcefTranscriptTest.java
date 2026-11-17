package org.agty.aiassistant.ui;

import com.intellij.testFramework.*;
import com.intellij.openapi.util.registry.Registry;
import com.intellij.ui.jcef.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import javax.swing.*;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Real JBR/CEF integration; opt in on a host providing a supported native runtime. */
@EnabledIfSystemProperty(named = "jcef.smoke", matches = "true")
@Timeout(60)
class JcefTranscriptTest {
    @Test void updatesKeepUnchangedDomNodesAndSelectionUntilUserCollapsesIt() throws Exception {
        TestApplicationManager.getInstance();
        Registry.get("ide.browser.jcef.enabled").setValue(true);
        Registry.get("ide.browser.jcef.headless.enabled").setValue(true);
        assertTrue(JBCefApp.isSupported(), "JBR must provide JCEF");
        var view = new AtomicReference<BrowserTranscript>();
        var result = new LinkedBlockingQueue<String>();
        var bridge = new AtomicReference<JBCefJSQuery>();
        var nativeBrowser = new AtomicReference<JBCefBrowser>();
        SwingUtilities.invokeAndWait(() -> {
            var transcript = new BrowserTranscript(value -> {}, true); view.set(transcript);
            try { Field field = BrowserTranscript.class.getDeclaredField("browser"); field.setAccessible(true); nativeBrowser.set((JBCefBrowser) field.get(transcript)); }
            catch (Exception e) { throw new AssertionError(e); }
            var query = JBCefJSQuery.create((JBCefBrowserBase) nativeBrowser.get()); bridge.set(query);
            query.addHandler(value -> { result.add(value); return new JBCefJSQuery.Response(""); });
            nativeBrowser.get().getCefBrowser().createImmediately();
            transcript.update(page("original"), false);
        });
        try {
            assertEquals("ready", evaluate(nativeBrowser.get(), bridge.get(), result,
                    "document.querySelector('[data-entry-id=one]')?'ready':'loading'", "ready"));
            assertEquals("original", evaluate(nativeBrowser.get(), bridge.get(), result,
                    "(()=>{window.saved=document.querySelector('[data-entry-id=one]');const s=getSelection(),r=document.createRange();r.selectNodeContents(saved);s.removeAllRanges();s.addRange(r);return s.toString()})()", null));
            SwingUtilities.invokeAndWait(() -> view.get().update(page("changed"), false));
            assertEquals("true:original", evaluate(nativeBrowser.get(), bridge.get(), result,
                    "(window.saved===document.querySelector('[data-entry-id=one]'))+':'+getSelection().toString()", null));
            evaluate(nativeBrowser.get(), bridge.get(), result, "(getSelection().removeAllRanges(),'collapsed')", null);
            assertEquals("changed", evaluate(nativeBrowser.get(), bridge.get(), result,
                    "document.querySelector('[data-entry-id=one]').textContent", "changed"));
            assertEquals("true", evaluate(nativeBrowser.get(), bridge.get(), result,
                    "(window.stable=document.querySelector('[data-entry-id=two]'),true)", null));
            SwingUtilities.invokeAndWait(() -> view.get().update(page("final"), false));
            assertEquals("true", evaluate(nativeBrowser.get(), bridge.get(), result,
                    "window.stable===document.querySelector('[data-entry-id=two]')", null));
            StringBuilder cards = new StringBuilder();
            for (int i = 0; i < 1000; i++) cards.append("<div data-entry-id='large-").append(i).append("'>").append("result ".repeat(100)).append("</div>");
            String large = "<html><head><style>body{color:black}</style></head><body>" + cards + "</body></html>";
            long started = System.nanoTime();
            SwingUtilities.invokeAndWait(() -> view.get().update(large, false));
            assertEquals("1000", evaluate(nativeBrowser.get(), bridge.get(), result, "document.getElementById('chat').children.length", "1000"));
            assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(15));
        } finally { SwingUtilities.invokeAndWait(() -> { bridge.get().dispose(); view.get().dispose(); }); }
    }
    private static String page(String text) {
        return "<html><head><style>body{color:black}</style></head><body><div data-entry-id='one'>" + text + "</div><div data-entry-id='two'>stable</div></body></html>";
    }
    private static final java.util.concurrent.atomic.AtomicInteger sequence = new java.util.concurrent.atomic.AtomicInteger();
    private static String evaluate(JBCefBrowser browser, JBCefJSQuery bridge, BlockingQueue<String> result, String expression, String expected) throws Exception {
        String prefix = sequence.incrementAndGet() + ":";
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        do {
            SwingUtilities.invokeAndWait(() -> browser.getCefBrowser().executeJavaScript(bridge.inject("'" + prefix + "'+String(" + expression + ")"), browser.getCefBrowser().getURL(), 0));
            String value = result.poll(1, TimeUnit.SECONDS);
            if (value != null && value.startsWith(prefix)) {
                value = value.substring(prefix.length());
                if (expected == null || expected.equals(value)) return value;
            }
        } while (System.nanoTime() < deadline);
        throw new AssertionError("CEF did not return expected value: " + expected);
    }
}
