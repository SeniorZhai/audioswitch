package com.twilio.audioswitch.audioswitch

import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.util.Log
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioSwitch

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        AudioSwitch(
            this, true,
            preferredDeviceList = listOf(
                AudioDevice.BluetoothHeadset::class.java,
                AudioDevice.WiredHeadset::class.java,
                AudioDevice.Speakerphone::class.java,
                AudioDevice.Earpiece::class.java
            )
        ).start { audioDevices, selectedAudioDevice ->
            Log.e("Audio", "$audioDevices $selectedAudioDevice")
        }
    }
}