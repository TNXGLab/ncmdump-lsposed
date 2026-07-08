package moe.tnxg.ncmdumplsposed.ui

import android.app.Activity
import android.os.Bundle
import android.widget.TextView
import moe.tnxg.ncmdumplsposed.R

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContentView(TextView(this).apply {
            text = getString(R.string.module_status)
            textSize = 16f
            setPadding(48, 48, 48, 48)
        })
    }
}
