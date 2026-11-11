package com.daftar.kredi

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class KrediApp : Application() {

    val db: KrediDb by lazy { KrediDb(this) }

    override fun onCreate() {
        super.onCreate()
        // Arabic (Morocco) everywhere, whatever the phone's language: right-to-left dialogs and pickers,
        // and Latin digits like on Moroccan receipts.
        AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags("ar-MA"))
    }
}

object Dates {
    fun day(time: Long): String = SimpleDateFormat("dd/MM/yyyy", Locale.US).format(Date(time))
    fun dayTime(time: Long): String = SimpleDateFormat("dd/MM/yyyy  HH:mm", Locale.US).format(Date(time))
}
