package com.twilio.audioswitch.bluetooth

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED
import android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED
import android.bluetooth.BluetoothHeadset.STATE_AUDIO_CONNECTED
import android.bluetooth.BluetoothHeadset.STATE_AUDIO_DISCONNECTED
import android.bluetooth.BluetoothHeadset.STATE_CONNECTED
import android.bluetooth.BluetoothHeadset.STATE_DISCONNECTED
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.os.Handler
import android.os.Looper
import androidx.annotation.VisibleForTesting
import com.twilio.audioswitch.AudioDevice
import com.twilio.audioswitch.AudioDeviceManager
import com.twilio.audioswitch.android.BluetoothDeviceWrapper
import com.twilio.audioswitch.android.BluetoothIntentProcessor
import com.twilio.audioswitch.android.BluetoothIntentProcessorImpl
import com.twilio.audioswitch.android.Logger
import com.twilio.audioswitch.android.PermissionsCheckStrategy
import com.twilio.audioswitch.android.SystemClockWrapper
import com.twilio.audioswitch.bluetooth.BluetoothHeadsetManager.HeadsetState.AudioActivated
import com.twilio.audioswitch.bluetooth.BluetoothHeadsetManager.HeadsetState.AudioActivating
import com.twilio.audioswitch.bluetooth.BluetoothHeadsetManager.HeadsetState.AudioActivationError
import com.twilio.audioswitch.bluetooth.BluetoothHeadsetManager.HeadsetState.Connected
import com.twilio.audioswitch.bluetooth.BluetoothHeadsetManager.HeadsetState.Disconnected

private const val TAG = "BluetoothHeadsetManager"
private const val PERMISSION_ERROR_MESSAGE = "Bluetooth unsupported, permissions not granted"

class BluetoothHeadsetManager

@VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
internal constructor(
    private val context: Context,
    private val logger: Logger,
    private val bluetoothAdapter: BluetoothAdapter,
    audioDeviceManager: AudioDeviceManager,
    var headsetListener: BluetoothHeadsetConnectionListener? = null,
    bluetoothScoHandler: Handler = Handler(Looper.getMainLooper()),
    systemClockWrapper: SystemClockWrapper = SystemClockWrapper(),
    private val bluetoothIntentProcessor: BluetoothIntentProcessor = BluetoothIntentProcessorImpl(),
    private var headsetProxy: BluetoothHeadset? = null,
    private val permissionsRequestStrategy: PermissionsCheckStrategy = DefaultPermissionsCheckStrategy(context),
    private var hasRegisteredReceivers: Boolean = false
) : BluetoothProfile.ServiceListener, BroadcastReceiver() {

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal var headsetState: HeadsetState = Disconnected
        set(value) {
            if (field != value) {
                field = value
                logger.d(TAG, "Headset state changed to ${field::class.simpleName}")
                if (value == Disconnected) enableBluetoothScoJob.cancelBluetoothScoJob()
            }
        }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val enableBluetoothScoJob: EnableBluetoothScoJob = EnableBluetoothScoJob(logger,
            audioDeviceManager, bluetoothScoHandler, systemClockWrapper)
    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal val disableBluetoothScoJob: DisableBluetoothScoJob = DisableBluetoothScoJob(logger,
            audioDeviceManager, bluetoothScoHandler, systemClockWrapper)

    companion object {
        internal fun newInstance(
            context: Context,
            logger: Logger,
            bluetoothAdapter: BluetoothAdapter?,
            audioDeviceManager: AudioDeviceManager
        ): BluetoothHeadsetManager? {
            return bluetoothAdapter?.let { adapter ->
                BluetoothHeadsetManager(context, logger, adapter, audioDeviceManager)
            } ?: run {
                logger.d(TAG, "Bluetooth is not supported on this device")
                null
            }
        }
    }

    override fun onServiceConnected(profile: Int, bluetoothProfile: BluetoothProfile) {
        headsetProxy = bluetoothProfile as BluetoothHeadset
        bluetoothProfile.connectedDevices.forEach { device ->
            logger.d(TAG, "Bluetooth " + device.name + " connected")
        }
        if (hasConnectedDevice()) {
            connect()
            headsetListener?.onBluetoothHeadsetStateChanged(getHeadsetName())
        }
    }

    override fun onServiceDisconnected(profile: Int) {
        logger.d(TAG, "Bluetooth disconnected")
        headsetState = Disconnected
        headsetListener?.onBluetoothHeadsetStateChanged()
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (isCorrectIntentAction(intent.action)) {
            intent.getHeadsetDevice()?.let { bluetoothDevice ->
                intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, STATE_DISCONNECTED).let { state ->
                    when (state) {
                        STATE_CONNECTED -> {
                            logger.d(
                                    TAG,
                                    "Bluetooth headset $bluetoothDevice connected")
                            connect()
                            headsetListener?.onBluetoothHeadsetStateChanged(bluetoothDevice.name)
                        }
                        STATE_DISCONNECTED -> {
                            logger.d(
                                    TAG,
                                    "Bluetooth headset $bluetoothDevice disconnected")
                            disconnect()
                            headsetListener?.onBluetoothHeadsetStateChanged()
                        }
                        STATE_AUDIO_CONNECTED -> {
                            logger.d(TAG, "Bluetooth audio connected on device $bluetoothDevice")
                            enableBluetoothScoJob.cancelBluetoothScoJob()
                            headsetState = AudioActivated
                            headsetListener?.onBluetoothHeadsetStateChanged()
                        }
                        STATE_AUDIO_DISCONNECTED -> {
                            logger.d(TAG, "Bluetooth audio disconnected on device $bluetoothDevice")
                            disableBluetoothScoJob.cancelBluetoothScoJob()
                            /*
                             * This block is needed to restart bluetooth SCO in the event that
                             * the active bluetooth headset has changed.
                             */
                            if (hasActiveHeadsetChanged()) {
                                enableBluetoothScoJob.executeBluetoothScoJob()
                            }

                            headsetListener?.onBluetoothHeadsetStateChanged()
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    fun start(headsetListener: BluetoothHeadsetConnectionListener) {
        if (hasPermissions()) {
            this.headsetListener = headsetListener

            bluetoothAdapter.getProfileProxy(
                context,
                this,
                BluetoothProfile.HEADSET
            )
            if (!hasRegisteredReceivers) {
                context.registerReceiver(
                    this, IntentFilter(ACTION_CONNECTION_STATE_CHANGED)
                )
                context.registerReceiver(
                    this, IntentFilter(ACTION_AUDIO_STATE_CHANGED)
                )
                hasRegisteredReceivers = true
            }
        } else {
            logger.w(TAG, PERMISSION_ERROR_MESSAGE)
        }
    }

    fun stop() {
        if (hasPermissions()) {
            headsetListener = null
            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, headsetProxy)
            if (hasRegisteredReceivers) {
                context.unregisterReceiver(this)
                hasRegisteredReceivers = false
            }
        } else {
            logger.w(TAG, PERMISSION_ERROR_MESSAGE)
        }
    }

    fun activate() {
        if (hasPermissions()) {
            if (headsetState == Connected || headsetState == AudioActivationError)
                enableBluetoothScoJob.executeBluetoothScoJob()
            else {
                logger.w(TAG, "Cannot activate when in the ${headsetState::class.simpleName} state")
            }
        } else {
            logger.w(TAG, PERMISSION_ERROR_MESSAGE)
        }
    }

    fun deactivate() {
        if (headsetState == AudioActivated) {
            disableBluetoothScoJob.executeBluetoothScoJob()
        } else {
            logger.w(TAG, "Cannot deactivate when in the ${headsetState::class.simpleName} state")
        }
    }

    fun hasActivationError(): Boolean {
        return if (hasPermissions()) {
            headsetState == AudioActivationError
        } else {
            logger.w(TAG, PERMISSION_ERROR_MESSAGE)
            false
        }
    }

    // TODO Remove bluetoothHeadsetName param
    fun getHeadset(bluetoothHeadsetName: String?): AudioDevice.BluetoothHeadset? {
        return if (hasPermissions()) {
            if (headsetState != Disconnected) {
                val headsetName = bluetoothHeadsetName ?: getHeadsetName()
                headsetName?.let { AudioDevice.BluetoothHeadset(it) }
                    ?: AudioDevice.BluetoothHeadset()
            } else
                null
        } else {
            logger.w(TAG, PERMISSION_ERROR_MESSAGE)
            null
        }
    }

    private fun isCorrectIntentAction(intentAction: String?) =
            intentAction == ACTION_CONNECTION_STATE_CHANGED || intentAction == ACTION_AUDIO_STATE_CHANGED

    private fun connect() {
        if (!hasActiveHeadset()) headsetState = Connected
    }

    private fun disconnect() {
        headsetState = when {
            hasActiveHeadset() -> {
                AudioActivated
            }
            hasConnectedDevice() -> {
                Connected
            }
            else -> {
                Disconnected
            }
        }
    }

    private fun hasActiveHeadsetChanged() = headsetState == AudioActivated && hasConnectedDevice() && !hasActiveHeadset()

    private fun getHeadsetName(): String? =
            headsetProxy?.let { proxy ->
                proxy.connectedDevices?.let { devices ->
                    when {
                        devices.size > 1 && hasActiveHeadset() -> {
                            val device = devices.find { proxy.isAudioConnected(it) }?.name
                            logger.d(TAG, "Device size > 1 with device name: $device")
                            device
                        }
                        devices.size == 1 -> {
                            val device = devices.first().name
                            logger.d(TAG, "Device size 1 with device name: $device")
                            device
                        }
                        else -> {
                            logger.d(TAG, "Device size 0")
                            null
                        }
                    }
                }
            }

    private fun hasActiveHeadset() =
            headsetProxy?.let { proxy ->
                proxy.connectedDevices?.let { devices ->
                    devices.any { proxy.isAudioConnected(it) }
                }
            } ?: false

    private fun hasConnectedDevice() =
            headsetProxy?.let { proxy ->
                proxy.connectedDevices?.let { devices ->
                    devices.isNotEmpty()
                }
            } ?: false

    private fun Intent.getHeadsetDevice(): BluetoothDeviceWrapper? =
            bluetoothIntentProcessor.getBluetoothDevice(this)?.let { device ->
                if (isHeadsetDevice(device)) device else null
            }

    private fun isHeadsetDevice(deviceWrapper: BluetoothDeviceWrapper): Boolean =
            deviceWrapper.deviceClass?.let { deviceClass ->
                deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE ||
                        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET ||
                        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO ||
                        deviceClass == BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES ||
                        deviceClass == BluetoothClass.Device.Major.UNCATEGORIZED
            } ?: false

    internal fun hasPermissions() = permissionsRequestStrategy.hasPermissions()

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal sealed class HeadsetState {
        object Disconnected : HeadsetState()
        object Connected : HeadsetState()
        object AudioActivating : HeadsetState()
        object AudioActivationError : HeadsetState()
        object AudioActivated : HeadsetState()
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal inner class EnableBluetoothScoJob(
        private val logger: Logger,
        private val audioDeviceManager: AudioDeviceManager,
        bluetoothScoHandler: Handler,
        systemClockWrapper: SystemClockWrapper
    ) : BluetoothScoJob(logger, bluetoothScoHandler, systemClockWrapper) {

        override fun scoAction() {
            logger.d(TAG, "Attempting to enable bluetooth SCO")
            audioDeviceManager.enableBluetoothSco(true)
            headsetState = AudioActivating
        }

        override fun scoTimeOutAction() {
            headsetState = AudioActivationError
            headsetListener?.onBluetoothHeadsetActivationError()
        }
    }

    @VisibleForTesting(otherwise = VisibleForTesting.PRIVATE)
    internal inner class DisableBluetoothScoJob(
        private val logger: Logger,
        private val audioDeviceManager: AudioDeviceManager,
        bluetoothScoHandler: Handler,
        systemClockWrapper: SystemClockWrapper
    ) : BluetoothScoJob(logger, bluetoothScoHandler, systemClockWrapper) {

        override fun scoAction() {
            logger.d(TAG, "Attempting to disable bluetooth SCO")
            audioDeviceManager.enableBluetoothSco(false)
            headsetState = Connected
        }

        override fun scoTimeOutAction() {
            headsetState = AudioActivationError
        }
    }

    internal class DefaultPermissionsCheckStrategy(private val context: Context) :
        PermissionsCheckStrategy {
        @SuppressLint("NewApi")
        override fun hasPermissions(): Boolean {
            return if (context.applicationInfo.targetSdkVersion <= android.os.Build.VERSION_CODES.R ||
                android.os.Build.VERSION.SDK_INT <= android.os.Build.VERSION_CODES.R
            ) {
                PERMISSION_GRANTED == context.checkPermission(
                    Manifest.permission.BLUETOOTH,
                    android.os.Process.myPid(),
                    android.os.Process.myUid()
                )
            } else {
                // for android 12/S or newer
                PERMISSION_GRANTED == context.checkPermission(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    android.os.Process.myPid(),
                    android.os.Process.myUid()
                )
            }
        }
    }
}
