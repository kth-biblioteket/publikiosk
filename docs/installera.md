# Installera en ny enhet

Så här görs en Android-enhet (t ex Elo) till en PubLiKiosk-kiosk. Allt görs en gång per enhet via USB
med `adb`. Det finns ingen Play Butik på enheterna: appen uppdaterar sig själv från GitHub efteråt,
men WebView måste installeras för hand (se [Underhåll](#underhåll)).

Du behöver:
- en dator med `adb` och `apksigner` (följer med Android SDK, t ex `~/Library/Android/sdk/platform-tools/adb`
  och `~/Library/Android/sdk/build-tools/<version>/apksigner`)
- en USB-kabel
- appen: `app-release.apk` från den senaste releasen på
  [GitHub](https://github.com/kth-biblioteket/publikiosk/releases/latest). **Inte** ett debug-bygge: det har
  felsökning påslagen och kan inte uppdatera sig själv
- Trichrome Library och Android System WebView, se steg 4

## 1. Återställ enheten

Appen kan bara bli *device owner* på en enhet utan konton, alltså direkt efter en fabriksåterställning.

- Elo: [Factory Data Reset, Elo Android I-Series 4.0](https://myelo.elotouch.com/support/s/article/Factory-Data-Reset-Elo-Android-I-Series-4-0-Devices)
- Eller med adb (om USB-felsökning redan är på):
  ```bash
  adb shell am broadcast -a android.intent.action.MASTER_CLEAR
  ```

En enhet som redan kör PubLiKiosk blockerar fabriksåterställning i kioskläget. Välj först
**Lämna kioskläge** i inställningsmenyn (se steg 7).

## 2. Första start

- Hoppa över allt som går: **logga inte in med något Google-konto** (då går det inte att göra appen till
  device owner).
- Wi-Fi: anslut manuellt till **KTH-IoT**: ange SSID och välj säkerhet **WPA/WPA2-Personal**. WPA3 fungerar inte.

## 3. Slå på USB-felsökning

Inställningar → Om enheten → tryck 7 gånger på *Build-nummer* → Utvecklaralternativ → **USB-felsökning** på.
Anslut USB och godkänn datorn på enheten. Kontrollera:

```bash
adb devices
```

## 4. Installera WebView

Enheternas inbyggda WebView är för gammal för webbapparna (t ex Wagnerguide visar en vit sida). Installera
en aktuell version.

1. **Arkitektur:**
   ```bash
   adb shell getprop ro.product.cpu.abi
   ```
2. **Ladda ner** [Trichrome Library](https://www.apkmirror.com/apk/google-inc/trichrome-library/) och
   [Android System WebView](https://www.apkmirror.com/apk/google-inc/android-system-webview/) i **exakt samma
   version** (alla fyra delar av versionsnumret, t ex 151.0.7922.202), för arkitekturen ovan och Android 10+.
   Finns inte Trichrome i samma version som den senaste WebView: ta den senaste version där båda finns.
3. **Kontrollera signaturen.** Filerna kommer från en tredjepartssajt och ska vara signerade av Google:
   ```bash
   apksigner verify --print-certs trichrome.apk | grep -E "DN|SHA-256"
   ```
   ```bash
   apksigner verify --print-certs webview.apk | grep -E "DN|SHA-256"
   ```
   De två filerna har olika certifikat. Så här ska det se ut (samma certifikat gäller för alla versioner):

   | Fil | Certifikat (DN) | SHA-256 |
   |---|---|---|
   | Trichrome Library | `CN=Android, OU=Android, O=Google Inc., …` | `b6198a8d5689b62b96a0aa3829ce2cc67d59497f78c469f8792b2cd9255490a1` |
   | WebView | `CN=webview, OU=Android, O=Google Inc., …` | `6faf3c4140407473400934d117815a21af1cfefc5c0bee61c858bc3d72ba6fe5` |

   WebView-certifikatet är detsamma som för den WebView som är förinstallerad på enheterna, och Android
   vägrar installera en WebView som inte är signerad likadant. Stämmer något värde inte: installera inte.
4. **Installera biblioteket först, sedan WebView:**
   ```bash
   adb install trichrome.apk
   ```
   ```bash
   adb install -r webview.apk
   ```
5. **Kontrollera** att den nya versionen används (står det fortfarande den gamla: vänta en stund eller starta
   om med `adb reboot`):
   ```bash
   adb shell dumpsys webviewupdate | grep -i "current webview package"
   ```

Har enheten redan en *nyare* WebView än den du vill installera (t ex en som inte fungerade): ta först bort
uppdateringen, då går den tillbaka till den inbyggda:

```bash
adb shell pm uninstall-system-updates com.google.android.webview
```

## 5. Installera appen och gör den till device owner

```bash
adb install app-release.apk
```
```bash
adb shell dpm set-device-owner se.kth.lib.publikiosk/.MyDeviceAdminReceiver
```

Får du *Not allowed to set the device owner because there are already some accounts*: enheten har ett konto,
börja om från steg 1.

## 6. Starta och välj PIN

Starta appen (PubLiKiosk). Den går direkt in i kioskläget och ber om en **PIN** för inställningsmenyn:
minst 6 siffror, inte samma siffra överallt. Skriv upp den på ett säkert ställe.

## 7. Inställningar

Inställningsmenyn öppnas med **5 snabba tryck i nedre högra hörnet** och PIN:en.

| Inställning | Att tänka på |
|---|---|
| URL för startsida | Måste börja med `https://`. Ange den exakt som servern svarar, t ex `https://apps.lib.kth.se/supportkiosk/` med `/` på slutet. Annars känns startsidan inte igen och tidsgränsen och loggningen startar inte. |
| Tillåtna webbplatser | Utöver startsidans webbplats. Standard `kth.se`; underdomäner ingår (`apps.lib.kth.se`, `spacefinder.lib.kth.se`). Länkar till andra webbplatser blockeras med *Sidan kan inte öppnas här*. |
| Timeout för inaktivitet | Millisekunder innan startsidan laddas om. |
| Timeout för inaktivitet extern web | Millisekunder innan appen går tillbaka från en annan sida än startsidan. |
| Rensa inloggningar och cookies vid inaktivitet | Låt stå på: annars kan nästa besökare komma åt den förras inloggningar. |
| Initial Scale, orientering, helskärm, startbild | Efter skärmen. |

Tryck **Spara**. **Lämna kioskläge** tar bort alla låsningar så att du kommer åt enhetens inställningar;
nästa gång appen startar låses enheten igen.

Fel PIN 5 gånger spärrar menyn i 1 minut, sedan längre, högst 15 minuter. Kiosken fungerar som vanligt under
tiden. En glömd PIN går i dag inte att återställa; då återstår fabriksåterställning (steg 1). Upplåsning från
publicomtools kommer när enheterna ansluts dit.

### Flera webbappar på en enhet

En enhet som styrs från publicomtools kan ha fler webbappar som besökaren växlar mellan. Startsidan
(`START_URL`) är hem-appen, och `APPS` listar de andra. De ställs bara in i publicomtools, inte i menyn.

- **Startsidans namn** och **Startsidans ikon** (`START_LABEL`, `START_ICON`): namnet och ikonen på hem-appens knapp. Tomt namn ger *Hem*, standardikonen är ett hus.
- **Webbappar** (`APPS`): i publicomtools redigeras de i en egen vy med ett kort per app (namn, adress, ikon och avancerat område) som du lägger till, tar bort och drar i ordning. Högst fem (sex med förstasida). Värdarna blir automatiskt tillåtna webbplatser. Under ytan sparas en app per rad som `Namn|https://adress/|ikon|område|beskrivning|namn_en|beskrivning_en`, där allt utom namn och adress är valfritt. Appen läser radbrytning som avgränsare, och komma om det inte finns någon radbrytning.
- **Område** anges som i *Appens område* (värd och sökväg). Tomt betyder adressens värd och sökväg som katalog.
- **Ikon** är något av namnen `house`, `search`, `map`, `map-pin`, `calendar`, `book-open`, `library`, `info`, `circle-help`, `printer`, `monitor`, `user`, `clock` och `graduation-cap` (Lucide). Utan ikon visas bara namnet.

Exempel:

```
Sök böcker|https://www.kth.se/biblioteket|search
Karta|https://wagnerguide.com/c/kth/kth|map
```

Med fler appar visas ramen alltid, med Tillbaka och en knapp per app, där den aktiva är markerad. Ett tryck på
en app ger en ny start i den: dess startsida, utan historik. Hem-appens knapp gör som Hem förut och rensar
sessionen. Utan `APPS` beter sig enheten som förut: ramen visas bara utanför appen. Ingen ram visas om
*Kiosknavigering* är *Aldrig* (skyltar).

### Förstasida med tjänster

Med **Börja med** (`HOME_MODE`) satt till *Förstasida med tjänster* visar enheten en egen förstasida innan
någon app öppnas, i stället för att starta i en app:

- Förstasidan visar ett stort kort per tjänst (namn, ikon och en kort beskrivning). Tjänsterna är `APPS`,
  och startsidan (`START_URL`) används inte. Högst sex tjänster.
- Ett tryck på ett kort öppnar tjänsten. Då visas ramen med Tillbaka, tjänstens namn och **Startsida**, som
  tar besökaren tillbaka till förstasidan (och rensar sessionen).
- Efter inaktivitet går enheten tillbaka till förstasidan, med samma varning "Är du kvar?" som förut.
- Rubrik, underrubrik och en valfri rad längst ner ställs in med `LAUNCHER_TITLE`, `LAUNCHER_SUBTITLE` och
  `LAUNCHER_FOOTER`, och på engelska med `LAUNCHER_TITLE_EN`, `LAUNCHER_SUBTITLE_EN` och `LAUNCHER_FOOTER_EN`.
  Tomma svenska texter får standardtext (Vad vill du göra?, Tryck på en tjänst för att börja). En tom engelsk
  text blir den svenska texten, och finns ingen sådan, standardtexten på engelska.
- Varje tjänst kan ha ett engelskt namn och en engelsk beskrivning. Saknas de visas de svenska.
- Knappen **English** byter språk på förstasidan och ramen tills enheten går tillbaka till förstasidan.
  Webbapparna själva byter inte språk.
- Har enheten bara en tjänst hoppar den över förstasidan och öppnar den direkt. Utan tjänster beter sig
  enheten som med *En app*.
- Texterna är satta i Figtree, KTH:s profiltypsnitt, som följer med i appen (licens: `docs/licenses/Figtree-OFL.txt`).
- Det finns ingen startbild längre (`SPLASH` är borttagen): förstasidan är det som visas när ingen använder enheten.

## 8. Stäng av USB-felsökning

När allt fungerar: välj **Lämna kioskläge**, gå till Utvecklaralternativ och stäng av **USB-felsökning**.
Annars kan den som når enhetens USB-port ansluta en dator, och dialogen *Tillåt USB-felsökning?* visas på
skärmen där vem som helst kan godkänna den. Starta sedan appen igen.

## Underhåll

- **Appen** uppdaterar sig själv när den startar (efter omstart, eller vid nästa besökare när startbilden är på)
  om det finns en nyare release på GitHub. Release sker när `main` ändras, se README.
- **WebView** uppdateras inte av sig själv. Den får säkerhetsrättningar hela tiden, så installera en ny version
  på enheterna regelbundet, t ex en gång per termin (steg 3–4, slå på USB-felsökning tillfälligt).
- Kontrollera att felsökning av WebView är avstängd på en enhet (inget svar = avstängd):
  ```bash
  adb shell cat /proc/net/unix | grep webview_devtools
  ```
