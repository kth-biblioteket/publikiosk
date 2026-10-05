# Public Library Kiosk
Kiosk-app för KTH Biblioteket: visar en webbapp i helskärm på publika Android-enheter (t ex Elo), med
appen som *device owner* i låst kioskläge.

**Installera en ny enhet:** se [docs/installera.md](docs/installera.md) (återställning, Wi-Fi, WebView,
appen, PIN, inställningar och underhåll).

## Release

Varje push till `main` bygger appen i GitHub Actions och publicerar `app-release.apk` som en release med
taggen `v<versionName>` från `app/build.gradle`. Enheterna installerar den själva nästa gång appen startar.

- Höj `versionCode` och `versionName` i `app/build.gradle` för varje ny version. Utan höjning skapas ingen
  ny release.
- En push till `main` är alltså en utrullning till alla enheter: arbeta på en gren och testa först.
- Ändringar som bara rör `docs/` eller `.md`-filer bygger ingenting.

## ADB-kommandon

```bash
adb shell am broadcast -a android.intent.action.MASTER_CLEAR
```
```bash
adb reboot recovery
```
```bash
adb shell dpm set-device-owner se.kth.lib.publikiosk/.MyDeviceAdminReceiver
```
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

Debug-bygget är bara för utveckling och test (felsökning av WebView och http mot emulatorn är påslaget).
På riktiga enheter: `app-release.apk` från den senaste releasen.

### För att ansluta till KTH-IoT

Anslut manuellt och ange SSID och WPA/WPA2-Personal.
- WPA3 fungerar INTE!

### Reset ELO
https://myelo.elotouch.com/support/s/article/Factory-Data-Reset-Elo-Android-I-Series-4-0-Devices

### Uppgradera WebView

Trichrome Library och WebView måste ha **exakt samma version**, och Trichrome installeras först. Kontrollera
signaturen innan du installerar, se [docs/installera.md](docs/installera.md#4-installera-webview).

- https://www.apkmirror.com/apk/google-inc/trichrome-library/
- https://www.apkmirror.com/apk/google-inc/android-system-webview/

```bash
adb install ~/Downloads/trichrome.apk
```
```bash
adb install -r ~/Downloads/webview.apk
```
