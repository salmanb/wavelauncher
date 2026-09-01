package com.salman.wavelauncher

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.provider.ContactsContract
import java.text.Collator

data class AppEntry(
    val label: String,
    val packageName: String,
    val activityName: String,
    val icon: Drawable?,
    val user: UserHandle? = null
) {
    val letter: String
        get() = label.take(1).uppercase()

    fun launchIntent(): Intent {
        val i = Intent(Intent.ACTION_MAIN)
        i.addCategory(Intent.CATEGORY_LAUNCHER)
        i.setClassName(packageName, activityName)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return i
    }

    fun launch(c: Context) {
        try {
            if (user != null) {
                val la = c.getSystemService(LauncherApps::class.java)
                if (la != null) {
                    la.startMainActivity(ComponentName(packageName, activityName), user, null, null)
                }
            } else {
                c.startActivity(launchIntent())
            }
        } catch (e: Exception) {
            // app missing or disabled
        }
    }
}

data class ContactEntry(val name: String, val uri: Uri)

object AppLoader {
    fun loadApps(c: Context): List<AppEntry> {
        val pm = c.packageManager
        val intent = Intent(Intent.ACTION_MAIN)
        intent.addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = pm.queryIntentActivities(intent, 0)
        val out = ArrayList<AppEntry>()
        for (ri in resolved) {
            val raw = ri.loadLabel(pm)
            if (raw == null) continue
            val label = raw.toString().trim()
            if (label.isEmpty()) continue
            if (ri.activityInfo.packageName == c.packageName) continue
            out.add(AppEntry(label, ri.activityInfo.packageName, ri.activityInfo.name, ri.loadIcon(pm)))
        }
        val collator = Collator.getInstance()
        val cmp = Comparator<AppEntry> { a: AppEntry, b: AppEntry -> collator.compare(a.label, b.label) }
        out.sortWith(cmp)
        return out
    }

    fun loadContacts(c: Context): List<ContactEntry> {
        val out = ArrayList<ContactEntry>()
        val granted = c.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return out
        val proj = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY
        )
        try {
            val cur = c.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI, proj, null, null,
                ContactsContract.Contacts.SORT_KEY_PRIMARY
            )
            if (cur != null) {
                while (cur.moveToNext()) {
                    val id = cur.getLong(0)
                    val name = cur.getString(1)
                    if (name != null) {
                        out.add(ContactEntry(name, Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_URI, id.toString())))
                    }
                }
                cur.close()
            }
        } catch (e: Exception) {
            // provider unavailable
        }
        return out
    }
}

object WorkApps {
    /** The work-profile handle, or null. Personal devices have at most one non-main profile. */
    fun workProfile(c: Context): UserHandle? {
        try {
            val um = c.getSystemService(UserManager::class.java)
            if (um == null) return null
            val me = Process.myUserHandle()
            val profiles = um.userProfiles
            for (h in profiles) {
                if (h != me) return h
            }
        } catch (e: Exception) {
            return null
        }
        return null
    }

    fun isPaused(c: Context, h: UserHandle): Boolean {
        try {
            val um = c.getSystemService(UserManager::class.java)
            if (um != null) return um.isQuietModeEnabled(h)
        } catch (e: Exception) {
            return false
        }
        return false
    }

    fun requestQuiet(c: Context, h: UserHandle, quiet: Boolean): Boolean {
        try {
            val um = c.getSystemService(UserManager::class.java)
            if (um != null) return um.requestQuietModeEnabled(quiet, h)
        } catch (e: Exception) {
            return false
        }
        return false
    }

    fun loadApps(c: Context, handle: UserHandle): List<AppEntry> {
        val out = ArrayList<AppEntry>()
        try {
            val la = c.getSystemService(LauncherApps::class.java)
            if (la == null) return out
            val pm = c.packageManager
            val list = la.getActivityList(null, handle)
            for (li in list) {
                val raw = li.label
                if (raw == null) continue
                val label = raw.toString().trim()
                if (label.isEmpty()) continue
                val cn = li.componentName
                out.add(AppEntry(label, cn.packageName, cn.className, li.getBadgedIcon(0), handle))
            }
            val cmp2 = Comparator<AppEntry> { a: AppEntry, b: AppEntry -> a.label.compareTo(b.label, ignoreCase = true) }
            out.sortWith(cmp2)
        } catch (e: Exception) {
            return out
        }
        return out
    }
}

fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
