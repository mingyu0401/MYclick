package com.myclick.core

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle

/** 设置页（模块自身进程）读写配置；被 Hook 进程读配置走 ConfigStore，不直接读这里。 */
object Prefs {
    private fun sp(context: Context): SharedPreferences =
        context.getSharedPreferences(Config.PREFS_FILE, Context.MODE_PRIVATE)

    fun read(context: Context): Config {
        val b = Bundle()
        val defaults = Config.default().toBundle()
        val all = sp(context).all
        for (key in defaults.keySet()) {
            when (val v = all[key]) {
                is Boolean -> b.putBoolean(key, v)
                is Float -> b.putFloat(key, v)
                is Int -> b.putInt(key, v)
                is String -> b.putString(key, v)
            }
        }
        return Config.fromBundle(b)
    }

    fun write(context: Context, config: Config) {
        val bundle = config.toBundle()
        sp(context).edit().apply {
            for (key in bundle.keySet()) {
                @Suppress("DEPRECATION")
                when (val v = bundle.get(key)) {
                    is Boolean -> putBoolean(key, v)
                    is Float -> putFloat(key, v)
                    is Int -> putInt(key, v)
                    is String -> putString(key, v)
                }
            }
        }.apply()
        TouchRelay.sendConfig(context, config)
    }
}
