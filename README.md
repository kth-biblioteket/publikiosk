# Public Library Kiosk
Kiosk-app för KTH Biblioteket

## ADB-kommandon
``` bash
adb shell am broadcast -a android.intent.action.MASTER_CLEAR
adb reboot recovery\n
adb shell dpm set-device-owner se.kth.lib.publikiosk/.MyDeviceAdminReceiver
adb install /Users/tholind/AndroidStudioProjects/PubLiKiosk/app/build/outputs/apk/debug/app-debug.apk
```

