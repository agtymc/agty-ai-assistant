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
    private final JBCefBrowser browser;
    private final JBCefJSQuery actions;
    private String latest = "";
    private boolean follow = true, resetScroll;
    private volatile boolean ready, disposed;
    BrowserTranscript(Consumer<String> activate) { this(activate, false); }
    BrowserTranscript(Consumer<String> activate, boolean offscreen) {
        browser = JBCefBrowser.createBuilder().setOffScreenRendering(offscreen).build();
        actions = JBCefJSQuery.create((JBCefBrowserBase) browser);
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
                + "const style=document.getElementById('chat-style');const css=doc.querySelector('style').textContent;if(style.textContent!==css)style.textContent=css;"
                + "const chat=document.getElementById('chat');const existing=new Map([...chat.children].map(n=>[n.dataset.entryId,n]));"
                + "const selected=n=>{const s=getSelection();return s&&!s.isCollapsed&&s.rangeCount&&n.contains(s.getRangeAt(0).commonAncestorContainer);};"
                + "window.agtyDeferred=window.agtyDeferred||new Map();let cursor=chat.firstElementChild;"
                + "for(const fresh of doc.body.children){const id=fresh.dataset.entryId;let node=existing.get(id);existing.delete(id);"
                + "if(!node){node=document.importNode(fresh,true);}else if(node.outerHTML!==fresh.outerHTML){"
                + "if(selected(node)){window.agtyDeferred.set(id,fresh.outerHTML);}else{node.innerHTML=fresh.innerHTML;node.className=fresh.className;window.agtyDeferred.delete(id);}}else{window.agtyDeferred.delete(id);}"
                + "if(node!==cursor)chat.insertBefore(node,cursor);cursor=node.nextElementSibling;}"
                + "for(const [id,node] of existing){if(selected(node))window.agtyDeferred.set(id,null);else node.remove();}"
                + "if(!window.agtySelectionListener){window.agtySelectionListener=true;document.addEventListener('selectionchange',()=>{"
                + "if(!getSelection().isCollapsed)return;for(const [id,html] of window.agtyDeferred){const node=[...chat.children].find(n=>n.dataset.entryId===id);"
                + "if(node){if(html===null)node.remove();else{const box=document.createElement('div');box.innerHTML=html;node.replaceWith(box.firstElementChild);}}}window.agtyDeferred.clear();});}"
                + "root.scrollTop=" + (resetScroll ? "root.scrollHeight" : "(" + follow + "&&bottom)?root.scrollHeight:old") + ";})();";
        resetScroll = false;
        browser.getCefBrowser().executeJavaScript(script, browser.getCefBrowser().getURL(), 0);
    }
    @Override public void dispose() { disposed = true; actions.dispose(); browser.dispose(); }
}
