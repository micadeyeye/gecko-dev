/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.toolbar;

import org.mozilla.gecko.R;
import org.mozilla.gecko.toolbar.BrowserToolbar.OnCommitListener;
import org.mozilla.gecko.toolbar.BrowserToolbar.OnDismissListener;
import org.mozilla.gecko.toolbar.BrowserToolbar.OnFilterListener;
import org.mozilla.gecko.CustomEditText;
import org.mozilla.gecko.CustomEditText.OnKeyPreImeListener;
import org.mozilla.gecko.InputMethods;
import org.mozilla.gecko.util.GamepadUtils;
import org.mozilla.gecko.util.StringUtils;

import android.content.Context;
import android.graphics.Rect;
import android.text.Editable;
import android.text.InputType;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.View.OnKeyListener;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

/**
* {@code ToolbarEditText} is the text entry used when the toolbar
* is in edit state. It handles all the necessary input method machinery
* as well as the tracking of different text types (empty, search, or url).
* It's meant to be owned by {@code ToolbarEditLayout}.
*/
public class ToolbarEditText extends CustomEditText
                             implements AutocompleteHandler {

    private static final String LOGTAG = "GeckoToolbarEditText";

    // Used to track the current type of content in the
    // text entry so that ToolbarEditLayout can update its
    // state accordingly.
    enum TextType {
        EMPTY,
        SEARCH_QUERY,
        URL
    }

    interface OnTextTypeChangeListener {
        public void onTextTypeChange(ToolbarEditText editText, TextType textType);
    }

    private final Context mContext;

    // Type of the URL bar go/search button
    private TextType mToolbarTextType;

    private OnCommitListener mCommitListener;
    private OnDismissListener mDismissListener;
    private OnFilterListener mFilterListener;
    private OnTextTypeChangeListener mTextTypeListener;

    // The previous autocomplete result returned to us
    private String mAutoCompleteResult = "";

    // The user typed part of the autocomplete result
    private String mAutoCompletePrefix = null;

    public ToolbarEditText(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;

        mToolbarTextType = TextType.EMPTY;
    }

    void setOnCommitListener(OnCommitListener listener) {
        mCommitListener = listener;
    }

    void setOnDismissListener(OnDismissListener listener) {
        mDismissListener = listener;
    }

    void setOnFilterListener(OnFilterListener listener) {
        mFilterListener = listener;
    }

    void setOnTextTypeChangeListener(OnTextTypeChangeListener listener) {
        mTextTypeListener = listener;
    }

    @Override
    public void onAttachedToWindow() {
        setOnKeyListener(new KeyListener());
        setOnKeyPreImeListener(new KeyPreImeListener());
        addTextChangedListener(new TextChangeListener());
    }

    @Override
    public void onFocusChanged(boolean gainFocus, int direction, Rect previouslyFocusedRect) {
        super.onFocusChanged(gainFocus, direction, previouslyFocusedRect);

        if (gainFocus) {
            resetAutocompleteState();
            return;
        }

        InputMethodManager imm = (InputMethodManager) mContext.getSystemService(Context.INPUT_METHOD_SERVICE);
        try {
            imm.hideSoftInputFromWindow(getWindowToken(), 0);
        } catch (NullPointerException e) {
            Log.e(LOGTAG, "InputMethodManagerService, why are you throwing"
                          + " a NullPointerException? See bug 782096", e);
        }
    }

    // Return early if we're backspacing through the string, or
    // have no autocomplete results
    @Override
    public final void onAutocomplete(final String result) {
        if (!isEnabled()) {
            return;
        }

        final String text = getText().toString();

        if (result == null) {
            mAutoCompleteResult = "";
            return;
        }

        if (!result.startsWith(text) || text.equals(result)) {
            return;
        }

        mAutoCompleteResult = result;
        getText().append(result.substring(text.length()));
        setSelection(text.length(), result.length());
    }

    @Override
    public void setEnabled(boolean enabled) {
        super.setEnabled(enabled);
        updateTextTypeFromText(getText().toString());
    }

    private void resetAutocompleteState() {
        mAutoCompleteResult = "";
        mAutoCompletePrefix = null;
    }

    private static boolean hasCompositionString(Editable content) {
        Object[] spans = content.getSpans(0, content.length(), Object.class);

        if (spans != null) {
            for (Object span : spans) {
                if ((content.getSpanFlags(span) & Spanned.SPAN_COMPOSING) != 0) {
                    // Found composition string.
                    return true;
                }
            }
        }

        return false;
    }

    private void setTextType(TextType textType) {
        mToolbarTextType = textType;

        if (mTextTypeListener != null) {
            mTextTypeListener.onTextTypeChange(this, textType);
        }
    }

    private void updateTextTypeFromText(String text) {
        if (text.length() == 0) {
            setTextType(TextType.EMPTY);
            return;
        }

        final TextType newType;
        if (StringUtils.isSearchQuery(text, mToolbarTextType == TextType.SEARCH_QUERY)) {
            newType = TextType.SEARCH_QUERY;
        } else {
            newType = TextType.URL;
        }
        setTextType(newType);
    }

    private class TextChangeListener implements TextWatcher {
        @Override
        public void afterTextChanged(final Editable s) {
            if (!isEnabled()) {
                return;
            }

            final String text = s.toString();

            boolean useHandler = false;
            boolean reuseAutocomplete = false;

            if (!hasCompositionString(s) && !StringUtils.isSearchQuery(text, false)) {
                useHandler = true;

                // If you're hitting backspace (the string is getting smaller
                // or is unchanged), don't autocomplete.
                if (mAutoCompletePrefix != null && (mAutoCompletePrefix.length() >= text.length())) {
                    useHandler = false;
                } else if (mAutoCompleteResult != null && mAutoCompleteResult.startsWith(text)) {
                    // If this text already matches our autocomplete text, autocomplete likely
                    // won't change. Just reuse the old autocomplete value.
                    useHandler = false;
                    reuseAutocomplete = true;
                }
            }

            // If this is the autocomplete text being set, don't run the filter.
            if (TextUtils.isEmpty(mAutoCompleteResult) || !mAutoCompleteResult.equals(text)) {
                if (mFilterListener != null) {
                    mFilterListener.onFilter(text, useHandler ? ToolbarEditText.this : null);
                }

                mAutoCompletePrefix = text;

                if (reuseAutocomplete) {
                    onAutocomplete(mAutoCompleteResult);
                }
            }

            updateTextTypeFromText(text);
        }

        @Override
        public void beforeTextChanged(CharSequence s, int start, int count,
                                      int after) {
            // do nothing
        }

        @Override
        public void onTextChanged(CharSequence s, int start, int before,
                                  int count) {
            // do nothing
        }
    }

    private class KeyPreImeListener implements OnKeyPreImeListener {
        @Override
        public boolean onKeyPreIme(View v, int keyCode, KeyEvent event) {
            // We only want to process one event per tap
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            if (keyCode == KeyEvent.KEYCODE_ENTER) {
                // If the edit text has a composition string, don't submit the text yet.
                // ENTER is needed to commit the composition string.
                final Editable content = getText();
                if (!hasCompositionString(content)) {
                    if (mCommitListener != null) {
                        mCommitListener.onCommit();
                    }

                    return true;
                }
            }

            if (keyCode == KeyEvent.KEYCODE_BACK) {
                // Drop the virtual keyboard.
                clearFocus();
                return true;
            }

            return false;
        }
    }

    private class KeyListener implements View.OnKeyListener {
        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            if (keyCode == KeyEvent.KEYCODE_ENTER || GamepadUtils.isActionKey(event)) {
                if (event.getAction() != KeyEvent.ACTION_DOWN) {
                    return true;
                }

                if (mCommitListener != null) {
                    mCommitListener.onCommit();
                }

                return true;
            } else if (GamepadUtils.isBackKey(event)) {
                if (mDismissListener != null) {
                    mDismissListener.onDismiss();
                }

                return true;
            }

            return false;
        }
    }
}
