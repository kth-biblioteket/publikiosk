# Public Library Kiosk
Kiosk-app för KTH Biblioteket

## ADB-kommandon
``` bash
adb shell am broadcast -a android.intent.action.MASTER_CLEAR
adb reboot recovery\n
adb shell dpm set-device-owner se.kth.lib.publikiosk/.MyDeviceAdminReceiver
adb install /Users/tholind/AndroidStudioProjects/PubLiKiosk/app/build/outputs/apk/debug/app-debug.apk
```

### Reset ELO
https://myelo.elotouch.com/support/s/article/Factory-Data-Reset-Elo-Android-I-Series-4-0-Devices

### Upgrade WebView
https://www.apkmirror.com/apk/google-inc/android-system-webview/
adb install ~/Downloads/trichrome133.apk
adb install -r ~/Downloads/webview133.apk