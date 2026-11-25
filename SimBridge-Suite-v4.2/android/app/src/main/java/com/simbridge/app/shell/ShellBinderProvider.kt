package com.simbridge.app.shell

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.IBinder
import com.simbridge.app.IShellTap
import com.simbridge.app.bridge.BridgeEventBus
import java.security.MessageDigest

class ShellBinderProvider : ContentProvider() {

    companion object {
        private const val SHELL_UID = 2000
        private const val ROOT_UID = 0

        @Volatile var expectedToken: String = ""
        @Volatile var shellTap: IShellTap? = null
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "bind_shell" || extras == null) return null

        val callingUid = Binder.getCallingUid()
        if (callingUid != SHELL_UID && callingUid != ROOT_UID) return null

        val expected = expectedToken
        val provided = extras.getString("token") ?: return null
        if (expected.isEmpty()) return null

        val match = MessageDigest.isEqual(
            expected.toByteArray(Charsets.UTF_8),
            provided.toByteArray(Charsets.UTF_8)
        )
        if (!match) return null

        val binder = extras.getBinder("binder") ?: return null
        val tap = IShellTap.Stub.asInterface(binder) ?: return null

        runCatching {
            binder.linkToDeath(
                object : IBinder.DeathRecipient {
                    override fun binderDied() {
                        if (shellTap?.asBinder() == binder) {
                            shellTap = null
                            BridgeEventBus.notifyShellBound(false)
                        }
                    }
                },
                0
            )
        }

        shellTap = tap
        BridgeEventBus.notifyShellBound(true)
        return Bundle().apply { putBoolean("ok", true) }
    }

    override fun query(u: Uri, p: Array<out String>?, s: String?, sa: Array<out String>?, so: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
