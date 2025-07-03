package ua.com.sdegroup.imoveprinter.util

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import ua.com.sdegroup.imoveprinter.R

fun showSystemPrintError(context: Context, fileName: String, message: String) {
  val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
  val channelId = "printer_errors"
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
    val channel = NotificationChannel(channelId, "Print Errors", NotificationManager.IMPORTANCE_HIGH)
    manager.createNotificationChannel(channel)
  }

  val notif = NotificationCompat.Builder(context, channelId)
    .setSmallIcon(android.R.drawable.stat_notify_error)
    .setContentTitle(context.getString(R.string.print_error_title, fileName))
    .setContentText(message)
    .setStyle(NotificationCompat.BigTextStyle().bigText(message))
    .setAutoCancel(true)
    .build()

  manager.notify(System.currentTimeMillis().toInt(), notif)
}
