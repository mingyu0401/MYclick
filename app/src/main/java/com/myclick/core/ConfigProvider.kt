package com.myclick.core

import android.content.ContentProvider
import android.content.ContentValues
import android.content.UriMatcher
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Bundle

/**
 * 跨进程配置出口。
 *
 * 被 Hook 的进程用：
 *   contentResolver.call(Uri.parse("content://com.myclick.config/config"), "config", null, null)
 * 一次拿到完整 Bundle；也保留 query() 形式便于 adb / 第三方调试。
 *
 * 注意：provider 必须 exported 且不设 android:permission，否则 system_server 与
 * 系统录屏进程（不同签名）拿不到读权限。
 */
class ConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        return when (method) {
            METHOD_CONFIG -> Prefs.read(ctx).toBundle()
            METHOD_PING -> Bundle().apply { putBoolean("ok", true) }
            else -> null
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val config = Prefs.read(context ?: return MatrixCursor(arrayOf("key", "value")))
        val cursor = MatrixCursor(arrayOf("key", "value"))
        config.toBundle().keySet().forEach { key ->
            @Suppress("DEPRECATION")
            cursor.addRow(arrayOf(key, config.toBundle().get(key).toString()))
        }
        return cursor
    }

    override fun getType(uri: Uri): String? = "vnd.android.cursor.dir/vnd.com.myclick.config"
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val METHOD_CONFIG = "config"
        const val METHOD_PING = "ping"
        val URI: Uri = Uri.parse("content://${Config.CONFIG_AUTHORITY}/config")
    }
}
