package com.telegramfiretv.ui

import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.fragment.app.FragmentActivity
import com.telegramfiretv.BuildConfig
import com.telegramfiretv.R

class AboutActivity : FragmentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(0xFF0E1418.toInt())
            setPadding(64, 48, 64, 48)
        }

        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.rick_image)
            adjustViewBounds = true
            layoutParams = LinearLayout.LayoutParams(400, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = 24 }
        })

        root.addView(TextView(this).apply {
            text = "FiregramTV"
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(0xFFFFFFFF.toInt())
            textSize = 30f
            gravity = Gravity.CENTER
        })

        root.addView(TextView(this).apply {
            text = "build ${BuildConfig.VERSION_NAME}"
            setTextColor(0xFFAAB4BE.toInt())
            textSize = 13f
            gravity = Gravity.CENTER
            setPadding(0, 4, 0, 32)
        })

        root.addView(TextView(this).apply {
            text = "App realizzata da zero con Claude, con un pizzico di follia ed un mix di odio che non fa mai male."
            setTextColor(0xFFE6EDF2.toInt())
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(48, 0, 48, 0)
        })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(0xFF0E1418.toInt())
            addView(root)
        })
    }
}
