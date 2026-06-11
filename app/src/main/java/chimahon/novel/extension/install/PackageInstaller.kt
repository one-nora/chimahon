package chimahon.novel.extension.install

import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.IntentSender
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class SystemPackageInstaller(
    private val context: Application,
) {
    private val packageInstaller = context.packageManager.packageInstaller

    suspend fun install(file: File, packageName: String): InstallStep {
        return installInternal(file, packageName)
    }

    suspend fun uninstall(packageName: String): InstallStep {
        return withInstallReceiver { sender ->
            packageInstaller.uninstall(packageName, sender)
        }
    }

    private suspend fun installInternal(file: File, packageName: String): InstallStep {
        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "File does not exist or can't be read: ${file.absolutePath}")
            return InstallStep.Error("File not found: ${file.absolutePath}")
        }
        Log.d(TAG, "Preparing to install: ${file.absolutePath}")
        Log.d(TAG, "File size: ${file.length()} bytes")
        var session: android.content.pm.PackageInstaller.Session? = null
        return try {
            val installParams = android.content.pm.PackageInstaller.SessionParams(
                android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL,
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                installParams.setRequireUserAction(
                    android.content.pm.PackageInstaller.SessionParams.USER_ACTION_REQUIRED,
                )
            }
            installParams.setAppPackageName(packageName)
            installParams.setSize(file.length())
            val sessionId = packageInstaller.createSession(installParams)
            Log.d(TAG, "Created session ID: $sessionId")
            session = packageInstaller.openSession(sessionId)
            val output = session.openWrite(packageName, 0, file.length())
            output.use {
                file.inputStream().use { input ->
                    val buffer = ByteArray(65536)
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } > 0) {
                        output.write(buffer, 0, bytesRead)
                    }
                }
                session.fsync(output)
            }
            withInstallReceiver { sender ->
                session.commit(sender)
                Log.d(TAG, "Session committed successfully")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install package: ${e.message}", e)
            InstallStep.Error("Install failed: ${e.message}")
        } finally {
            try { session?.close() } catch (_: Exception) {}
        }
    }

    private suspend fun withInstallReceiver(
        block: suspend (IntentSender) -> Unit,
    ): InstallStep {
        val deferred = CompletableDeferred<InstallStep>()
        val receiver = InstallResultReceiver(context, deferred)
        val uid = SystemClock.elapsedRealtime()
        val action = "chimahon.INSTALL_APK_$uid"

        val intent = Intent(action).apply {
            setPackage(context.packageName)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val broadcast = PendingIntent.getBroadcast(context, 0, intent, flags)

        val sessionCallback = object : PackageInstaller.SessionCallback() {
            override fun onCreated(p0: Int) {}
            override fun onBadgingChanged(p0: Int) {}
            override fun onActiveChanged(p0: Int, p1: Boolean) {}
            override fun onProgressChanged(p0: Int, p1: Float) {}
            override fun onFinished(p0: Int, result: Boolean) {
                when (result) {
                    true -> deferred.complete(InstallStep.Success)
                    else -> deferred.complete(InstallStep.Idle)
                }
            }
        }
        packageInstaller.registerSessionCallback(sessionCallback)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            context.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, IntentFilter(action))
        }

        return try {
            block(broadcast.intentSender)
            deferred.await()
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {}
            broadcast.cancel()
            packageInstaller.unregisterSessionCallback(sessionCallback)
        }
    }

    private class InstallResultReceiver(
        val context: Application,
        val deferred: CompletableDeferred<InstallStep>,
    ) : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            intent ?: return
            val status = intent.getIntExtra(
                PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE,
            )
            val statusMessage = intent.getStringExtra(
                PackageInstaller.EXTRA_STATUS_MESSAGE,
            ) ?: "Unknown status"

            when (status) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    val confirmationIntent =
                        intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
                    if (confirmationIntent == null) {
                        Log.w(TAG, "No confirmation intent for $intent")
                        deferred.complete(InstallStep.Error("Missing confirmation intent"))
                        return
                    }
                    confirmationIntent.addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP,
                    )
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        if (confirmationIntent.component == null) {
                            val resolveInfo = context.packageManager
                                .resolveActivity(confirmationIntent, 0)
                            if (resolveInfo?.activityInfo != null) {
                                confirmationIntent.component = ComponentName(
                                    resolveInfo.activityInfo.packageName,
                                    resolveInfo.activityInfo.name,
                                )
                            }
                        }
                    }
                    try {
                        context.startActivity(confirmationIntent)
                    } catch (e: Throwable) {
                        Log.w(TAG, "Error showing installation dialog: ${e.message}")
                        deferred.complete(InstallStep.Error("Install dialog: ${e.message}"))
                    }
                }
                PackageInstaller.STATUS_SUCCESS -> {
                    deferred.complete(InstallStep.Success)
                }
                PackageInstaller.STATUS_FAILURE_ABORTED -> {
                    deferred.complete(InstallStep.Error("Installation aborted"))
                }
                PackageInstaller.STATUS_FAILURE_BLOCKED -> {
                    deferred.complete(InstallStep.Error("Installation blocked"))
                }
                PackageInstaller.STATUS_FAILURE_CONFLICT -> {
                    deferred.complete(InstallStep.Error("Installation conflict"))
                }
                PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> {
                    deferred.complete(InstallStep.Error("Incompatible package"))
                }
                PackageInstaller.STATUS_FAILURE_STORAGE -> {
                    deferred.complete(InstallStep.Error("Storage error"))
                }
                PackageInstaller.STATUS_FAILURE_INVALID -> {
                    deferred.complete(InstallStep.Error("Invalid package"))
                }
                else -> {
                    deferred.complete(
                        InstallStep.Error(
                            "Package installer failed. Status: $status, Message: $statusMessage",
                        ),
                    )
                }
            }
        }
    }

    companion object {
        private const val TAG = "SystemPackageInstaller"
    }
}
