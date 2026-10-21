package com.devilved.tonetunner.asr;

import android.media.AudioDeviceInfo;
import android.media.AudioManager;

import java.util.ArrayList;
import java.util.List;

/** Maps Android's connected inputs to {@link MicRouter} inputs; only real microphones are offered. */
final class MicDevices {
    private MicDevices() {}

    static MicRouter.Kind kindOf(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return MicRouter.Kind.BUILTIN;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
                return MicRouter.Kind.BLUETOOTH;
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_ACCESSORY:
            case AudioDeviceInfo.TYPE_LINE_ANALOG:
            case AudioDeviceInfo.TYPE_LINE_DIGITAL:
                return MicRouter.Kind.WIRED;
            case AudioDeviceInfo.TYPE_DOCK:
                return MicRouter.Kind.OTHER;
            default:
                // Telephony, tuners, remote submix and echo-reference inputs are not microphones.
                return null;
        }
    }

    static List<AudioDeviceInfo> microphones(AudioManager manager) {
        List<AudioDeviceInfo> result = new ArrayList<>();
        if (manager == null) return result;
        AudioDeviceInfo[] devices;
        try {
            devices = manager.getDevices(AudioManager.GET_DEVICES_INPUTS);
        } catch (RuntimeException e) {
            return result;
        }
        if (devices == null) return result;
        for (AudioDeviceInfo device : devices) {
            if (device != null && kindOf(device.getType()) != null) result.add(device);
        }
        return result;
    }

    static List<MicRouter.Input> inputs(List<AudioDeviceInfo> devices) {
        List<MicRouter.Input> inputs = new ArrayList<>();
        for (AudioDeviceInfo device : devices) {
            inputs.add(new MicRouter.Input(device.getId(), kindOf(device.getType()), device.getAddress()));
        }
        return inputs;
    }

    static AudioDeviceInfo find(List<AudioDeviceInfo> devices, MicRouter.Input input) {
        if (input == null) return null;
        for (AudioDeviceInfo device : devices) if (device.getId() == input.id) return device;
        return null;
    }
}
