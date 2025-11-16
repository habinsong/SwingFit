// Prefs.kt
package com.example.swingfit

import android.content.Context

object Prefs {
    const val PREFS = "swingfit_prefs"
    const val KEY_AUTO_LOGIN = "auto_login"
    const val KEY_EMAIL = "login_email"
    const val KEY_PASSWORD = "login_password" // 저장을 안 쓰면 제거해도 OK

    fun clearAutoLogin(ctx: Context) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean(KEY_AUTO_LOGIN, false)
            .remove(KEY_EMAIL)
            .remove(KEY_PASSWORD)
            .apply()
    }
}