package dev.standby.app

import android.content.Context

class Prefs(context: Context) {
    private val sp = context.getSharedPreferences("standby", Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = sp.getBoolean("enabled", false)
        set(v) = sp.edit().putBoolean("enabled", v).apply()
    var wirelessOnly: Boolean
        get() = sp.getBoolean("wirelessOnly", true)
        set(v) = sp.edit().putBoolean("wirelessOnly", v).apply()
    /** 0 automatic (light sensor), 1 always, 2 never. */
    var nightMode: Int
        get() = sp.getInt("nightMode", 0)
        set(v) = sp.edit().putInt("nightMode", v).apply()
    var showSeconds: Boolean
        get() = sp.getBoolean("showSeconds", false)
        set(v) = sp.edit().putBoolean("showSeconds", v).apply()
    var photoSeconds: Int
        get() = sp.getInt("photoSeconds", 30)
        set(v) = sp.edit().putInt("photoSeconds", v).apply()
    var font: Int
        get() = sp.getInt("font", 1)
        set(v) = sp.edit().putInt("font", v).apply()
    var use24h: Boolean
        get() = sp.getBoolean("use24h", true)
        set(v) = sp.edit().putBoolean("use24h", v).apply()
    var photos: Set<String>
        get() = sp.getStringSet("photos", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("photos", v).apply()

    // last position of every pager, so StandBy reopens where it was left
    var page: Int
        get() = sp.getInt("page", 0)
        set(v) = sp.edit().putInt("page", v).apply()
    var clockStyle: Int
        get() = sp.getInt("clockStyle", 0)
        set(v) = sp.edit().putInt("clockStyle", v).apply()
    var accent: Int
        get() = sp.getInt("accent", 1)
        set(v) = sp.edit().putInt("accent", v).apply()
}
