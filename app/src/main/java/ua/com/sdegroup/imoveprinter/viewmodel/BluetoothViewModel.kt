package ua.com.sdegroup.imoveprinter.viewmodel

  import android.Manifest
  import android.annotation.SuppressLint
  import android.bluetooth.BluetoothAdapter
  import android.bluetooth.BluetoothDevice
  import android.bluetooth.BluetoothManager
  import android.content.BroadcastReceiver
  import android.content.Context
  import android.content.Intent
  import android.content.IntentFilter
  import android.content.pm.PackageManager
  import android.os.Build
  import android.os.Handler
  import android.os.Looper
  import android.util.Log
  import android.widget.Toast
  import androidx.lifecycle.ViewModel
  import androidx.lifecycle.viewModelScope
  import kotlinx.coroutines.flow.MutableStateFlow
  import kotlinx.coroutines.flow.StateFlow
  import kotlinx.coroutines.flow.asStateFlow
  import kotlinx.coroutines.launch

  sealed class BluetoothState {
    object Idle : BluetoothState()
    object Discovering : BluetoothState()
    object Pairing : BluetoothState()
    data class Bonded(val deviceAddress: String) : BluetoothState()
    data class Error(val message: String) : BluetoothState()
  }

  class BluetoothViewModel : ViewModel() {
    private val TAG = "BluetoothViewModel"

    private val _bluetoothDevices = MutableStateFlow<List<BluetoothDevice>>(emptyList())
    val bluetoothDevices: StateFlow<List<BluetoothDevice>> = _bluetoothDevices.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _bluetoothState = MutableStateFlow<BluetoothState>(BluetoothState.Idle)
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState.asStateFlow()

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var _context: Context? = null

    private val bluetoothReceiver = object : BroadcastReceiver() {
      @SuppressLint("MissingPermission")
      override fun onReceive(context: Context?, intent: Intent?) {
        when (intent?.action) {
          BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
            _isRefreshing.value = true
            Log.d(TAG, "Discovery started")
          }
          BluetoothDevice.ACTION_FOUND -> {
            val device: BluetoothDevice? =
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            device?.let { newDevice ->
              if (!isDeviceAlreadyAdded(newDevice.address)) {
                _bluetoothDevices.value = _bluetoothDevices.value + newDevice
                Log.d(TAG, "Device found: ${newDevice.name ?: "Unknown"} - ${newDevice.address}")
              }
            }
          }
          BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
            _isRefreshing.value = false
            _bluetoothState.value = BluetoothState.Idle
            Log.d(TAG, "Discovery finished")
          }
          BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
            val device: BluetoothDevice? =
              intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
            when (device?.bondState) {
              BluetoothDevice.BOND_BONDED -> {
                Log.d(TAG, "Bonded with ${device.name ?: "Unknown"} - ${device.address}")
                _bluetoothState.value = BluetoothState.Bonded(device.address)
              }
              BluetoothDevice.BOND_BONDING -> {
                Log.d(TAG, "Bonding with ${device.name ?: "Unknown"} - ${device.address}")
                _bluetoothState.value = BluetoothState.Pairing
              }
              BluetoothDevice.BOND_NONE -> {
                Log.d(TAG, "Bonding failed or cancelled with ${device.name ?: "Unknown"} - ${device.address}")
                if (_bluetoothState.value is BluetoothState.Pairing) {
                  _bluetoothState.value = BluetoothState.Error("Pairing failed or cancelled.")
                }
              }
            }
          }
        }
      }
    }

    @SuppressLint("MissingPermission")
    fun initializeBluetooth(context: Context) {
      if (_context == null) {
        _context = context.applicationContext
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter

        if (bluetoothAdapter == null) {
          _bluetoothState.value = BluetoothState.Error("Bluetooth not supported on this device.")
          Toast.makeText(context, "Bluetooth not supported", Toast.LENGTH_LONG).show()
          return
        }

        val filter = IntentFilter().apply {
          addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
          addAction(BluetoothDevice.ACTION_FOUND)
          addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
          addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        }
        _context?.registerReceiver(bluetoothReceiver, filter)
        Log.d(TAG, "Bluetooth receiver registered")
      }
    }

    @SuppressLint("MissingPermission")
    fun startDiscovery(context: Context) {
      initializeBluetooth(context)

      if (!hasBluetoothPermissions()) {
        _bluetoothState.value = BluetoothState.Error("Bluetooth permissions not granted.")
        return
      }
      val adapter = bluetoothAdapter
      if (adapter == null) {
        _bluetoothState.value = BluetoothState.Error("Bluetooth adapter not initialized.")
        return
      }
      if (!adapter.isEnabled) {
        _bluetoothState.value = BluetoothState.Error("Bluetooth is not enabled.")
        return
      }

      if (adapter.isDiscovering) {
        adapter.cancelDiscovery()
        Log.d(TAG, "Canceled ongoing discovery to start a new one.")
      }

      _bluetoothDevices.value = emptyList()
      _isRefreshing.value    = true
      _bluetoothState.value  = BluetoothState.Discovering

      Handler(Looper.getMainLooper()).postDelayed({
        adapter.startDiscovery()
        Log.d(TAG, "Starting Bluetooth discovery")
      }, 500)
    }


    @SuppressLint("MissingPermission")
    fun pairDevice(device: BluetoothDevice) {
      if (!hasBluetoothPermissions()) {
        _bluetoothState.value = BluetoothState.Error("Bluetooth permissions not granted2.")
        return
      }
      if (bluetoothAdapter == null || !bluetoothAdapter!!.isEnabled) {
        _bluetoothState.value = BluetoothState.Error("Bluetooth is not enabled or adapter not found.")
        return
      }

      viewModelScope.launch {
        if (device.bondState == BluetoothDevice.BOND_BONDED) {
          _bluetoothState.value = BluetoothState.Bonded(device.address)
        } else {
          _bluetoothState.value = BluetoothState.Pairing
          if (bluetoothAdapter!!.isDiscovering) {
            bluetoothAdapter!!.cancelDiscovery()
          }
          val success = device.createBond()
          if (!success) {
            _bluetoothState.value = BluetoothState.Error("Failed to initiate pairing.")
            Log.e(TAG, "createBond failed for ${device.address}")
          }
        }
      }
    }

    private fun isDeviceAlreadyAdded(address: String): Boolean {
      return _bluetoothDevices.value.any { it.address == address }
    }

    private fun hasBluetoothPermissions(): Boolean {
      if (_context == null) {
          Log.e(TAG, "Context is null. Cannot check permissions.")
          return false
      }

      return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          val scanPermissionGranted = _context!!.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
          val connectPermissionGranted = _context!!.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

          Log.d(TAG, "BLUETOOTH_SCAN granted: $scanPermissionGranted")
          Log.d(TAG, "BLUETOOTH_CONNECT granted: $connectPermissionGranted")

          scanPermissionGranted && connectPermissionGranted
      } else {
          val locationPermissionGranted = _context!!.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

          Log.d(TAG, "ACCESS_FINE_LOCATION granted: $locationPermissionGranted")

          locationPermissionGranted
      }
    }

    @SuppressLint("MissingPermission")
    override fun onCleared() {
      super.onCleared()
      _context?.unregisterReceiver(bluetoothReceiver)
      if (bluetoothAdapter?.isDiscovering == true) {
        bluetoothAdapter?.cancelDiscovery()
      }
      _context = null
      Log.d(TAG, "BluetoothViewModel cleared and receiver unregistered")
    }

    fun onPermissionsGranted(context: Context) {
      initializeBluetooth(context)
      startDiscovery(context)
    }

    @SuppressLint("MissingPermission")
    fun restartDiscovery(context: Context) {
        startDiscovery(context)
    }
  }