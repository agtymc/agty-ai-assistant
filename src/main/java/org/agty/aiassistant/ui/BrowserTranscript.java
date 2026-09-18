package org.agty.aiassistant.ui;

import com.google.gson.Gson;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.ui.jcef.*;
import org.cef.browser.CefBrowser;
import org.cef.browser.CefFrame;
import org.cef.handler.CefLoadHandlerAdapter;
import javax.swing.*;
import java.util.function.Consumer;

/** Local document only: no remote scripts, images, frames or network connections. */
final class BrowserTranscript implements Disposable {
    private final JBCefBrowser browser = new JBCefBrowser();
    private final JBCefJSQuery actions = JBCefJSQuery.create(browser);
    private String latest = "";
    private boolean follow = true, resetScroll;
    private volatile boolean ready, disposed;
    BrowserTranscript(Consumer<String> activate) {
        actions.addHandler(value -> {
            ApplicationManager.getApplication().invokeLater(() -> { if (!disposed) activate.accept(value); });
            return new JBCefJSQuery.Response("");
        });
        browser.getJBCefClient().addLoadHandler(new CefLoadHandlerAdapter() {
            @Override public void onLoadEnd(CefBrowser b, CefFrame frame, int status) {
                if (frame.isMain()) ApplicationManager.getApplication().invokeLater(() -> {
                    if (disposed) return;
                    ready = true;
                    browser.getCefBrowser().executeJavaScript("document.addEventListener('click',e=>{const a=e.target.closest('a');if(a){e.preventDefault();const href=a.getAttribute('href');"
                            + actions.inject("href") + "}});", b.getURL(), 0);
                    flush();
                });
            }
        }, browser.getCefBrowser());
        browser.loadHTML("<!doctype html><html><head><meta charset='UTF-8'><meta http-equiv='Content-Security-Policy' content=\"default-src 'none'; style-src 'unsafe-inline'; script-src 'unsafe-inline'; img-src 'none'; connect-src 'none'; frame-src 'none'; base-uri 'none'; form-action 'none'\"><style id='chat-style'></style></head><body><main id='chat'></main></body></html>");
    }
    JComponent component() { return browser.getComponent(); }
    void update(String html, boolean autoScroll) { latest = html; follow = autoScroll; flush(); }
    void resetScroll() { resetScroll = true; }
    private void flush() {
        if (!ready || disposed || latest.isEmpty()) return;
        String script = "(()=>{const root=document.scrollingElement;const old=root.scrollTop;const bottom=root.scrollHeight-old-innerHeight<60;"
                + "const doc=new DOMParser().parseFromString(" + new Gson().toJson(latest) + ",'text/html');"
                + "document.getElementById('chat-style').textContent=doc.querySelector('style').textContent;"
                + "document.getElementById('chat').innerHTML=doc.body.innerHTML;"
                + "root.scrollTop=" + (resetScroll ? "root.scrollHeight" : "(" + follow + "&&bottom)?root.scrollHeight:old") + ";})();";
        resetScroll = false;
        browser.getCefBrowser().executeJavaScript(script, browser.getCefBrowser().getURL(), 0);
    }
    @Override public void dispose() { disposed = true; actions.dispose(); browser.dispose(); }
}
