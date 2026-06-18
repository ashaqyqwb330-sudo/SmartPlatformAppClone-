package com.example.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.InputMethodManager
import android.widget.Toast

class TransparentActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.showInputMethodPicker()
        } catch (e: Exception) {
            Toast.makeText(this, "خطأ: ${e.message}", Toast.LENGTH_SHORT).show()
        }
        finish()
    }
}
