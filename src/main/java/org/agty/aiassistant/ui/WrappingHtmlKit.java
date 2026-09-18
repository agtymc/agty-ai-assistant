package org.agty.aiassistant.ui;

import javax.swing.text.*;
import javax.swing.text.html.*;

/** Allow even long identifiers to wrap to the available chat width. */
final class WrappingHtmlKit extends HTMLEditorKit {
    private final ViewFactory factory = new HTMLFactory() {
        @Override public View create(Element element) {
            View view = super.create(element);
            if (view.getClass() == InlineView.class) return new InlineView(element) {
                @Override public float getMinimumSpan(int axis) {
                    return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis);
                }
            };
            return view;
        }
    };
    @Override public ViewFactory getViewFactory() { return factory; }
}
