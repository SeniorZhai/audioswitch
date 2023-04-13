package com.twilio.audioswitch.exmple

import android.Manifest
import android.annotation.SuppressLint
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import com.tbruyelle.rxpermissions2.RxPermissions
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch

class MainActivity : AppCompatActivity() {
    @SuppressLint("SetTextI18n", "CheckResult")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val text = this.findViewById<TextView>(R.id.title)
        RxPermissions(this)
            .request(Manifest.permission.BLUETOOTH_CONNECT).subscribe({ granted ->
                if (granted) {
                    val audioSwitch = AudioSwitch(
                        this, true,
                        preferredDeviceList = listOf(
                            AudioDevice.BluetoothHeadset::class.java,
                            AudioDevice.WiredHeadset::class.java,
                            AudioDevice.Speakerphone::class.java,
                            AudioDevice.Earpiece::class.java
                        )
                    )
                    Log.e("Audio switch", "${audioSwitch.bluetoothHeadsetManager?.hasActivationError()}")
                    audioSwitch.start { audioDevices, selectedAudioDevice ->
                        text.text = "$audioDevices $selectedAudioDevice"
                        Log.e("Audio switch", "$audioDevices $selectedAudioDevice")
                    }
                } else {
                    // Todo
                }
            }, {
                // Todo
            })
    }
}