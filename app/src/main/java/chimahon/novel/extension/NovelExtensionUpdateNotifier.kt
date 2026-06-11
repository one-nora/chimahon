package chimahon.novel.extension

import android.content.Context
import android.graphics.BitmapFactory
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.data.notification.NotificationReceiver
import eu.kanade.tachiyomi.data.notification.Notifications
import eu.kanade.tachiyomi.util.system.cancelNotification
import eu.kanade.tachiyomi.util.system.notify

class NovelExtensionUpdateNotifier(private val context: Context) {

    fun promptUpdates(names: List<String>) {
        context.notify(
            Notifications.ID_UPDATES_TO_EXTS,
            Notifications.CHANNEL_EXTENSIONS_UPDATE,
        ) {
            setContentTitle("${names.size} novel extension update(s) available")
            val extNames = names.joinToString(", ")
            setContentText(extNames)
            setStyle(NotificationCompat.BigTextStyle().bigText(extNames))
            setSmallIcon(R.drawable.ic_extension_24dp)
            setColor(ContextCompat.getColor(context, R.color.ic_launcher))
            setLargeIcon(BitmapFactory.decodeResource(context.resources, R.drawable.chimahon))
            setContentIntent(NotificationReceiver.openExtensionsPendingActivity(context))
            setAutoCancel(true)
        }
    }

    fun dismiss() {
        context.cancelNotification(Notifications.ID_UPDATES_TO_EXTS)
    }
}
