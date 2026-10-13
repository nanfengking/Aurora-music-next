package com.aurora.musicnext;

import android.content.Context;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Connected outputs are not proof of the active route or the negotiated Bluetooth codec. */
final class AudioOutputInfo {
    static JSONObject status(Context context) throws JSONException {
        AudioManager manager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        JSONArray devices = new JSONArray();
        if (manager != null) {
            for (android.media.AudioDeviceInfo device : manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (devices.length() >= 16) break;
                devices.put(new JSONObject().put("name", device.getProductName().toString()).put("kind", kind(device.getType())));
            }
        }
        return new JSONObject().put("devices", devices).put("systemVolume", manager == null ? 0 : manager.getStreamVolume(AudioManager.STREAM_MUSIC))
            .put("systemVolumeMax", manager == null ? 0 : manager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }
    private static String kind(int type) {
        switch (type) {
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER: case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER_SAFE: return "speaker";
            case AudioDeviceInfo.TYPE_BUILTIN_EARPIECE: return "earpiece";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP: case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET: case AudioDeviceInfo.TYPE_BLE_SPEAKER: return "bluetooth";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET: case AudioDeviceInfo.TYPE_WIRED_HEADPHONES: return "wired";
            case AudioDeviceInfo.TYPE_USB_DEVICE: case AudioDeviceInfo.TYPE_USB_ACCESSORY: case AudioDeviceInfo.TYPE_USB_HEADSET: return "usb";
            default: return "otherOutput";
        }
    }
}
