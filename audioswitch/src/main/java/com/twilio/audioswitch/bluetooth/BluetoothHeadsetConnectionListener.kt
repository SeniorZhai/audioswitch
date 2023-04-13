package com.twilio.audioswitch.bluetooth

interface BluetoothHeadsetConnectionListener {
    fun onBluetoothHeadsetStateChanged(headsetName: String? = null)
    fun onBluetoothHeadsetActivationError()
}
