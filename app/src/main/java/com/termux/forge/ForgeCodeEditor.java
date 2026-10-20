package com.termux.forge;

import android.content.Context;

import androidx.annotation.NonNull;

import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.Cursor;
import io.github.rosemoe.sora.widget.CodeEditor;

/**
 * Sora's CodeEditor with "type over" for closing symbols: typing ) ] } " ' or ` when the same
 * character is right after the cursor moves past it instead of inserting a duplicate. Without
 * this, the editor's auto-inserted closers turn print(x) into print(x)).
 */
public class ForgeCodeEditor extends CodeEditor {

    public ForgeCodeEditor(Context context) {
        super(context);
    }

    @Override
    public void commitText(@NonNull CharSequence text, boolean applyAutoIndent, boolean applySymbolCompletion) {
        if (text.length() == 1 && isCloser(text.charAt(0))) {
            Cursor cursor = getCursor();
            if (!cursor.isSelected()) {
                Content content = getText();
                int line = cursor.getLeftLine();
                int column = cursor.getLeftColumn();
                if (column < content.getColumnCount(line) && content.charAt(line, column) == text.charAt(0)) {
                    setSelection(line, column + 1);
                    return;
                }
            }
        }
        super.commitText(text, applyAutoIndent, applySymbolCompletion);
    }

    private static boolean isCloser(char c) {
        return c == ')' || c == ']' || c == '}' || c == '"' || c == '\'' || c == '`';
    }
}
