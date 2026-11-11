package com.daftar.kredi

import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/** Shows one invoice photo full screen; tap to close. */
class PhotoActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_NAME = "photo"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_photo)
        val file = PhotoStore.file(this, intent.getStringExtra(EXTRA_NAME).orEmpty())
        val bitmap = file?.let { BitmapFactory.decodeFile(it.path) }
        if (bitmap == null) {
            Toast.makeText(this, R.string.photo_missing, Toast.LENGTH_SHORT).show()
            finish()
            return
        }
        findViewById<ImageView>(R.id.photo).apply {
            setImageBitmap(bitmap)
            setOnClickListener { finish() }
        }
    }
}
