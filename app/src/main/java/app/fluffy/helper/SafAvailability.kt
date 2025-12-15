package app.fluffy.helper

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

object SafAvailability {

    // Known “stub” packages that intentionally claim common intents on some device types
    private val STUB_PACKAGES = setOf(
        "com.android.tv.frameworkpackagestubs",
        "com.android.car.frameworkpackagestubs",
    )

    // sometimes OEMs fork/rename classes, so keeping package name is the main signal.
    private fun isStubHandler(pkg: String?, cls: String?): Boolean {
        if (pkg in STUB_PACKAGES) return true
        val n = (cls ?: "")
        return n.contains($$"Stubs$DocumentsStub") ||
               n.contains("DocumentsUIStubWithResult") ||
               n.contains("DocumentsUIStub")
    }

    fun canUseSaf(context: Context, intent: Intent): Boolean {
        val pm = context.packageManager

        val ri = if (Build.VERSION.SDK_INT >= 33) {
            pm.resolveActivity(
                intent,
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
            )
        } else {
            @Suppress("DEPRECATION")
            pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } ?: return false

        val pkg = ri.activityInfo?.packageName
        val cls = ri.activityInfo?.name
        return !isStubHandler(pkg, cls)
    }

    fun canOpenDocumentTree(context: Context): Boolean =
        canUseSaf(context, Intent(Intent.ACTION_OPEN_DOCUMENT_TREE))

    fun canCreateDocument(context: Context, mime: String = "*/*"): Boolean =
        canUseSaf(
            context,
            Intent(Intent.ACTION_CREATE_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType(mime)
        )
}