package ua.com.sdegroup.imoveprinter.util

import android.net.ConnectivityManager
import android.net.Network

object PrinterNetworkHolder {
  var networkCallback: ConnectivityManager.NetworkCallback? = null
  var wifiNetwork: Network? = null
}
