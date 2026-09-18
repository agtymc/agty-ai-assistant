package org.agty.aiassistant.ui;

import org.agty.aiassistant.settings.ChatAppearance;
import javax.swing.*;
import java.awt.*;

final class ChatStyles {
    static String css(ChatAppearance.Data d, boolean browser) {
        Color background = UIManager.getColor("EditorPane.background");
        Color foreground = UIManager.getColor("EditorPane.foreground");
        if (background == null) background = Color.WHITE;
        if (foreground == null) foreground = Color.BLACK;
        boolean dark = background.getRed() + background.getGreen() + background.getBlue() < 384;
        String bg = d.ideColors ? CodeHighlighting.hex(background) : color(d.background, "#1e1f22");
        String fg = d.ideColors ? CodeHighlighting.hex(foreground) : color(d.foreground, "#dfe1e5");
        String user = d.ideColors ? dark ? "#34363c" : "#e9edf5" : color(d.userBackground, "#34363c");
        String accent = d.ideColors ? dark ? "#8cb4ff" : "#245fc4" : color(d.accent, "#8cb4ff");
        String codeBg = CodeHighlighting.hex(com.intellij.openapi.editor.colors.EditorColorsManager.getInstance().getGlobalScheme().getDefaultBackground());
        String css = "body{font-family:'" + family(d.font) + "',sans-serif;font-size:" + d.fontSize + "px;background:" + bg + ";color:" + fg + ";margin:0;padding:18px;}"
                + "a{color:" + accent + ";text-decoration:none;}p{margin:8px 0;} .card{margin-bottom:" + d.spacing + "px;padding:8px 0;}"
                + ".user{background:" + user + ";padding:12px;margin-left:36px;} .title{font-weight:bold;margin-bottom:8px;}"
                + ".usertext{white-space:pre-wrap;tab-size:4;overflow-wrap:anywhere;}"
                + ".activity{padding:8px;border-left:2px solid " + accent + ";font-size:" + d.activitySize + "px;} .activity p{font-size:" + d.activitySize + "px;}"
                + "code{font-family:'" + family(d.codeFont) + "',monospace;font-size:" + d.codeSize + "px;}"
                + ".codehead{font-size:12px;padding:9px 12px;background:" + codeBg + ";color:" + accent + ";margin-top:12px;}"
                + ".codehead a{float:right;} .codeblock{padding:12px;background:" + codeBg + ";font-size:" + d.codeSize + "px;}"
                + "td,th{padding:6px;border:1px solid #777777;}blockquote{margin-left:0;padding-left:12px;border-left:2px solid " + accent + ";}"
                + ".copy{font-size:11px;font-weight:normal;} .welcome{text-align:center;padding:40px 10px;}";
        if (browser) css += "*{box-sizing:border-box;}body{line-height:1.55;overflow-wrap:anywhere;}#chat{max-width:1100px;margin:auto;}"
                + ".card{width:100%;} .user{width:fit-content;max-width:85%;margin-left:auto;border-radius:12px;padding:10px 14px;}"
                + ".activity{border-radius:6px;opacity:.85;} .title{display:flex;gap:12px;align-items:center;font-size:" + d.activitySize + "px;} .title .copy{margin-left:auto;opacity:.65;}"
                + ".codehead{border:1px solid #7775;border-bottom:0;border-radius:9px 9px 0 0;}"
                + ".codeblock{border:1px solid #7775;border-top:0;border-radius:0 0 9px 9px;overflow-x:auto;line-height:1.55;}"
                + ".codeblock code{white-space:" + (d.wrapCode ? "pre-wrap;overflow-wrap:anywhere;" : "pre;overflow-wrap:normal;") + "}"
                + "table{border-collapse:collapse;max-width:100%;}h1,h2,h3{line-height:1.3;}::-webkit-scrollbar{width:8px;height:8px;}::-webkit-scrollbar-thumb{background:#8885;border-radius:8px;}";
        return css;
    }
    private static String family(String value) { return value.replaceAll("[^\\p{L}\\p{N} _-]", ""); }
    private static String color(String value, String fallback) { return value.matches("#[0-9a-fA-F]{6}") ? value : fallback; }
}
