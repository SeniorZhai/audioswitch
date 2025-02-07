package com.twilio.audioswitch

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.media.AudioManager.OnAudioFocusChangeListener
import com.nhaarman.mockitokotlin2.mock
import com.nhaarman.mockitokotlin2.verify
import com.nhaarman.mockitokotlin2.whenever
import com.twilio.audioswitch.AudioDevice.Earpiece
import com.twilio.audioswitch.AudioDevice.Speakerphone
import com.twilio.audioswitch.AudioDevice.WiredHeadset
import com.twilio.audioswitch.android.BuildWrapper
import com.twilio.audioswitch.bluetooth.BluetoothHeadsetManager
import com.twilio.audioswitch.wired.WiredHeadsetReceiver
import org.hamcrest.CoreMatchers
import org.hamcrest.MatcherAssert.assertThat

open class BaseTest {
    internal val bluetoothClass = mock<BluetoothClass> {
        whenever(mock.deviceClass).thenReturn(BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE)
    }
    internal val expectedBluetoothDevice = mock<BluetoothDevice> {
        whenever(mock.name).thenReturn(DEVICE_NAME)
        whenever(mock.bluetoothClass).thenReturn(bluetoothClass)
    }
    internal val context = mock<Context>()
    internal val logger = UnitTestLogger()
    internal val audioManager = setupAudioManagerMock()
    internal val bluetoothAdapter = mock<BluetoothAdapter>()
    internal val audioDeviceChangeListener = mock<AudioDeviceChangeListener>()
    internal val buildWrapper = mock<BuildWrapper>()
    internal val audioFocusRequest = mock<AudioFocusRequestWrapper>()
    internal val defaultAudioFocusChangeListener = mock<OnAudioFocusChangeListener>()
    internal val audioDeviceManager = AudioDeviceManager(context, logger, audioManager, buildWrapper,
            audioFocusRequest, defaultAudioFocusChangeListener)
    internal val wiredHeadsetReceiver = WiredHeadsetReceiver(context, logger)
    internal var handler = setupScoHandlerMock()
    internal var systemClockWrapper = setupSystemClockMock()
    internal val headsetProxy = mock<BluetoothHeadset>()
    internal val preferredDeviceList = listOf(AudioDevice.BluetoothHeadset::class.java, WiredHeadset::class.java,
            Earpiece::class.java, Speakerphone::class.java)
    internal val permissionsStrategyProxy = setupPermissionsCheckStrategy()
    internal var headsetManager: BluetoothHeadsetManager = BluetoothHeadsetManager(
        context,
        logger,
        bluetoothAdapter,
        audioDeviceManager,
        bluetoothScoHandler = handler,
        systemClockWrapper = systemClockWrapper,
        headsetProxy = headsetProxy,
        permissionsRequestStrategy = permissionsStrategyProxy
    )

    internal var audioSwitch = AudioSwitch(
        context = context,
        logger = logger,
        audioDeviceManager = audioDeviceManager,
        wiredHeadsetReceiver = wiredHeadsetReceiver,
        headsetManager = headsetManager,
        audioFocusChangeListener = defaultAudioFocusChangeListener,
        preferredDeviceList = preferredDeviceList
    )

    internal fun assertBluetoothHeadsetTeardown() {
        assertThat(headsetManager.headsetListener, CoreMatchers.`is`(CoreMatchers.nullValue()))
        verify(bluetoothAdapter).closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy)
        verify(context).unregisterReceiver(headsetManager)
    }

    internal fun simulateNewBluetoothHeadsetConnection(
        bluetoothDevice: BluetoothDevice = expectedBluetoothDevice
    ) {
        val intent = mock<Intent> {
            whenever(mock.action).thenReturn(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            whenever(mock.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED))
                    .thenReturn(BluetoothHeadset.STATE_CONNECTED)
            whenever(mock.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))
                    .thenReturn(bluetoothDevice)
        }
        headsetManager.onReceive(context, intent)
    }

    internal fun simulateDisconnectedBluetoothHeadsetConnection(
        bluetoothDevice: BluetoothDevice = expectedBluetoothDevice
    ) {
        val intent = mock<Intent> {
            whenever(mock.action).thenReturn(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            whenever(mock.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED))
                .thenReturn(BluetoothHeadset.STATE_DISCONNECTED)
            whenever(mock.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE))
                .thenReturn(bluetoothDevice)
        }
        headsetManager.onReceive(context, intent)
    }
}
