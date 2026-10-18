package com.example.validation

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView

/** Debug-only editor for installed-keyboard tests. No real user document is opened or changed. */
class SyntheticEditorActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 40, 24, 24) }
        layout.addView(TextView(this).apply { text = "Synthetic keyboard test · No personal content"; textSize = 18f })
        val editor = EditText(this).apply {
            id = View.generateViewId()
            contentDescription = "Synthetic TypeRight test editor"
            minLines = 6; textSize = 18f
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setText(savedInstanceState?.getString("syntheticText") ?: intent.getStringExtra("syntheticText") ?: "i recieved the report and i will send it tommorow.")
            val start = intent.getIntExtra("selectionStart", text.length).coerceIn(0, text.length)
            val end = intent.getIntExtra("selectionEnd", start).coerceIn(start, text.length)
            setSelection(start, end)
        }
        layout.addView(editor, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(layout)
        editor.requestFocus()
        editor.postDelayed({ getSystemService(InputMethodManager::class.java).showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT) }, 400)
    }
}
