package com.marsa.pdfreader

import android.app.Activity
import android.os.Bundle
import android.widget.TextView

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val title = TextView(this).apply {
            text = "MARSA PDF Reader"
            textSize = 24f
            setPadding(32, 48, 32, 32)
        }

        setContentView(title)
    }
}
