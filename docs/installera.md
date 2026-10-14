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

En enhet som styrs från publicomtools kan ha fler webbappar som besökaren växlar mellan. I läget *En app* är den
**första tjänsten i `APPS` hem**, som i Linux-kiosken: Hem, *Börja om* och inaktivitet går dit, och den har sitt eget
namn, engelska namn, ikon, område och engelska adress. De ställs bara in i publicomtools, inte i menyn.

- **Äldre inställningar** (3.20.0): har enheten en `START_URL` som inte redan finns bland tjänsterna i `APPS` läggs
  startsidan först som en tjänst: namn `START_LABEL` (tomt: *Hem* / *Home*), ikon `START_ICON`, område `APP_SCOPE`,
  adress `START_URL`. Befintliga inställningar fungerar därför oförändrade tills de flyttats till `APPS`.
- **Startsidans namn (English)** (`START_LABEL_EN`, 3.21.0): startknappens engelska rad, mindre under det svenska namnet. Tom: ingen engelsk rad när `START_LABEL` är ifylld; när båda är tomma visas standardtexterna (*Hem* / *Home*, *Startsida* / *Home page*). Gäller även felsidans startknapp och, för äldre inställningar med `START_URL`, den första fliken.
- **Startsidans namn** och **Startsidans ikon** (`START_LABEL`, `START_ICON`, ikonen är vilken Lucide-ikon som helst): namnet och ikonen på Startsida-knappen,
  som visas med förstasida och när enheten bara har en tjänst (då ingen flik). Med flera tjänster har varje flik sitt eget namn och sin egen ikon.
- **Webbappar** (`APPS`): i publicomtools redigeras de i en egen vy med ett kort per app (namn, adress, ikon och avancerat område) som du lägger till, tar bort och drar i ordning. Högst sex som flikar, hem inräknat (tolv med förstasida). Värdarna blir automatiskt tillåtna webbplatser. Under ytan sparas en app per rad som `Namn|https://adress/|ikon|område|beskrivning|namn_en|beskrivning_en|adress_en`, där allt utom namn och adress är valfritt. Appen läser radbrytning som avgränsare, och komma om det inte finns någon radbrytning. Innehåller någon text tecknen `|`, komma, `"` eller radbrytning sparar publicomtools i stället en JSON-lista (`[{"name":"…","url":"https://…","icon":"…","scope":"…","desc":"…","nameEn":"…","descEn":"…","urlEn":"https://…"}]`), som kräver 3.14.0.
- **Område** anges som i *Appens område* (värd och sökväg). Tomt betyder adressens värd och sökväg som katalog.
- **Ikon** är namnet på en Lucide-ikon (lucide.dev/icons, version 1.48.0, alla 2 118 namn inklusive äldre alias som `circle-help` och `home`), t ex `search`, `building-2` eller `book-marked`. Samma ikoner som i Linux-kiosken och publicomtools. Utan ikon, eller med ett okänt namn, visas informationsikonen (på kort, flikar och startknappen), som i Linux-kiosken. Ikonerna ligger i appen (`assets/lucide-icons.txt`, genererad av `tools/gen-lucide.mjs` ur lucide-react 1.48.0, ISC); vid en uppgradering byts versionen på alla tre ställen samtidigt.

Exempel:

```
Sök böcker|https://www.kth.se/biblioteket|search
Karta|https://wagnerguide.com/c/kth/kth|map
```

Med fler än en tjänst visas ramen alltid, med Tillbaka och en flik per tjänst (hem först), där den aktiva är markerad. Ett tryck på
en app ger en ny start i den: dess startsida, utan historik. Hem-appens knapp gör som Hem förut och rensar
sessionen. Med en enda tjänst beter sig enheten som förut: ramen visas bara utanför appen. Ingen ram visas om
*Kiosknavigering* är *Aldrig* (skyltar).

### Förstasida med tjänster

Med **Börja med** (`HOME_MODE`) satt till *Förstasida med tjänster* visar enheten en egen förstasida innan
någon app öppnas, i stället för att starta i en app:

- Förstasidan visar ett stort kort per tjänst (namn, ikon och en kort beskrivning). Tjänsterna är `APPS`,
  och startsidan (`START_URL`) används inte. Högst tolv tjänster. `LAUNCHER_PER_PAGE` (2, 4, 6, 8, 10 eller 12, standard 4) bestämmer hur många som visas samtidigt: korten anpassar kolumner, rader och storlek efter antalet och skärmen (rutor eller breda rader, beskrivningen döljs när korten blir små). Finns det fler tjänster blir det flera sidor med prickar under, som besökaren sveper mellan. Startsida går tillbaka till första sidan.
- Ett tryck på ett kort öppnar tjänsten. Då visas ramen med Tillbaka, tjänstens namn och **Startsida**, som
  tar besökaren tillbaka till förstasidan (och rensar sessionen).
- Efter inaktivitet går enheten tillbaka till förstasidan, med samma varning "Är du kvar?" som förut.
- Rubrik, underrubrik och en valfri rad längst ner ställs in med `LAUNCHER_TITLE`, `LAUNCHER_SUBTITLE` och
  `LAUNCHER_FOOTER`, och på engelska med `LAUNCHER_TITLE_EN`, `LAUNCHER_SUBTITLE_EN` och `LAUNCHER_FOOTER_EN`.
  Tomma svenska texter får standardtext (Vad vill du göra?, Tryck på en tjänst för att börja). En tom engelsk
  text blir den svenska texten, och finns ingen sådan, standardtexten på engelska.
- Varje tjänst kan ha ett engelskt namn och en engelsk beskrivning. Saknas de visas de svenska.
- Varje tjänst kan också ha en **engelsk adress** (`adress_en`, `urlEn` i JSON): den öppnas i stället för den vanliga när besökaren valt English, t ex `https://tjänst.kth.se/?lang=en`. Den måste vara https och ligga på en tillåten webbplats (värden läggs till automatiskt), och är den ogiltig eller tom öppnas den vanliga adressen. Området gäller båda adresserna om det anges; annars räknas båda adressernas värd och sökväg som inne i tjänsten. Kräver 3.16.0. Med flera appar i ramen (utan förstasida) öppnas den vanliga adressen, eller den engelska om `LANGUAGE` är English.
- Knappen **English** byter språk på förstasidan (och tjänstens namn i ramen) tills enheten går tillbaka till förstasidan.
  Webbapparna själva byter inte språk.
- **Ramen är tvåspråkig** (3.19.0): Tillbaka, Hem/Startsida, flikarna, "Är du kvar?", spärr-arket och felsidan visar svenska stort och engelska mindre under, så att ingen behöver välja språk för att hitta rätt. Flikarnas engelska namn är `namn_en` på tjänsten; saknas det visas bara det svenska namnet. Står `START_LABEL` på hem-appen visas det på en rad. Liggande skärm har ikonen till vänster i knappen; stående skärm, eller fler än fem flikar, har ikonen ovanför texten och lika breda flikar. Namn som inte ryms kortas med …
- Har enheten bara en tjänst hoppar den över förstasidan och öppnar den direkt. Utan tjänster beter sig
  enheten som med *En app*.
- **Informationsfält** längst ner (3.15.0): upp till fyra fält i en ljusblå rad, ställda in med `LAUNCHER_FIELD_1` till `LAUNCHER_FIELD_4`. Varje fält är en rad `Etikett|typ|värde|Label`: typen `text` visar värdet som det står, `url` hämtar en text från en https-adress (som måste ligga på en tillåten webbplats och svara med ren text, aldrig HTML) och `clock` visar klockan. Exempel: `Öppet idag|text|8–19|Open today`. Adresserna hämtas var `LAUNCHER_REFRESH`:e minut (standard 1) medan förstasidan visas. Får en adress inget svar ligger den senaste texten kvar i en timme, sedan visas ett streck. Har något fält angetts döljs textraden `LAUNCHER_FOOTER`.
- **Tjänststatistik** (3.22.0): varje besök som skickas till publicomtools har ett valfritt fält `apps` med tiden besökaren haft varje tjänst vald, `[{"url":"<tjänstens adress i APPS>","name":"<tjänstens namn>","s":<sekunder>}]` (högst 36). I läget *En app* räknas den valda fliken (hem är första tjänsten), på förstasidan den tjänst vars kort öppnades; tiden på själva förstasidan räknas inte. Lämnar besökaren tjänstens område via en länk räknas tiden på tjänsten som var vald, och går besökaren till en annan av tjänsterna räknas den därifrån. Bara tjänstens konfigurerade adress (den vanliga, inte `adress_en`) och namn skickas, aldrig sidor, sökningar eller andra adresser. Tiden räknas fram till sista aktiviteten, och tjänster med 0 sekunder utelämnas.
- **Skylt** (3.25.0): med `HOME_MODE=signage` visas sidorna i `SIGN_PAGES` i tur och ordning i helskärm, bara visa: all inmatning (tryck, mushjul, tangenter) ignoreras och inga länkar följs. `SIGN_PAGES` är en JSON-lista `[{"url":"https://…","s":30}]` eller en rad per sida `https://adress|sekunder` (högst 36, bara https; tomt visar startsidan), `SIGN_SECONDS` (standard 30, 5-3600) gäller när sekunder saknas, och `SIGN_JS` (standard på) tillåter skript. **Schema** (`SIGN_SCHEDULE`, JSON): `{"lists":{"Dag":[{"url":"https://…","s":30}]},"slots":[{"list":"Dag","days":[1,2,3,4,5],"from":"08:00","to":"19:00"},{"list":"Bokrea","dates":["2026-10-20","2026-10-31"]}],"off":false}`. `days` är 1-7 (måndag-söndag), `from` ingår och `to` ingår inte (`24:00` = midnatt), `dates` är första och sista dag (båda ingår), och ett fält som saknas betyder alltid. En rad med okänd lista, ogiltiga dagar, tider eller datum, eller `from` efter `to`, hoppas över. Bland raderna som gäller nu vinner den första med `dates`, annars den första utan; gäller ingen rad (eller är listan tom) visas `SIGN_PAGES` (standardlistan), och är `off` på svart skärm. Enhetens lokala tid, kontrollerad var 20:e sekund; vid byte av lista börjar skylten direkt på listans första sida. Ogiltig eller saknad `SIGN_SCHEDULE` ger bara `SIGN_PAGES`. Listornas webbplatser tillåts automatiskt, och okända fält i sidposter (t.ex. `media`) ignoreras. Sidor kan vara uppladdade bilder, filmer och pdf-sidor hos publicomtools (vanliga https-adresser; för film är `s` filmens längd). Två webbvyer ligger över varandra, båda synliga: nästa sida laddas bakom den som visas en stund före bytet och byts in först när den hunnit rita klart (högst 10 sekunders väntan). En sida som inte går att ladda visar felsidan utan knappar och laddas om av sig själv var 30:e sekund, medan rotationen går vidare. Sidorna ritas för skärmens riktiga storlek (`INITIAL_SCALE` gäller). Sidornas egna webbplatser tillåts automatiskt; deras delresurser hämtas bara från tillåtna webbplatser (`ALLOWED_HOSTS`) och webbplatserna i `APPS`, så KTH:s skyltsidor behöver t.ex. `kth.se`, `cloud.timeedit.net`, `ajax.googleapis.com`, `cdn.jsdelivr.net`, `code.jquery.com`, `fonts.googleapis.com` och `fonts.gstatic.com`. Omdirigeringar följs bara till tillåtna webbplatser. Ingen besöksstatistik och ingen förstasida, navigering, tangentbord eller attract loop. Skillnad mot Linux: cookies och lagring delas mellan vyerna (Android WebView har en gemensam).
- **Attract loop** (`ATTRACT_URL`, `ATTRACT_AFTER`, `ATTRACT_REFRESH`, `ATTRACT_JS`): står förstasidan orörd i `ATTRACT_AFTER` sekunder (standard 120, 0 stänger av) visas en helskärmssida från en https-adress, till exempel ett bildspel eller en film utan ljud. Första trycket tar bort den, går inte vidare till något under och räknas inte som ett besök. Sidan visas i samma isolerade vy som fotradens webbsida (kan inte navigera, ingen brygga mot appen, skript av som standard) och ritas för 1280 pixlar bredd liggande (800 stående). Den laddas om var `ATTRACT_REFRESH`:e minut medan förstasidan visas, och är pausad när den inte syns. Svarar adressen inte visas förstasidan som vanligt. Gäller bara med förstasida. Bilder, filmer och typsnitt som sidan hämtar måste ligga på tillåtna webbplatser (en film från en annan värd blockeras), så lägg medierna på samma värd som sidan eller lägg värden i tillåtna webbplatser.
- **Webbsida i fotraden** (`LAUNCHER_HTML_URL`): en hel https-sida i stället för informationsfälten, med höjden `LAUNCHER_HTML_HEIGHT` (skisspixlar, standard 100). Sidan ritas för 1280 pixlar bredd liggande (800 stående) och skalas efter skärmen. Den visas i en egen vy som inte kan navigera, inte har någon brygga mot appen och bara hämtar delresurser från tillåtna webbplatser. Skript (`LAUNCHER_HTML_JS`) och tryck (`LAUNCHER_HTML_TOUCH`) är av som standard. Svarar adressen inte (nätfel eller svar 400 eller högre) döljs sidan och informationsfälten visas i stället. Cookies delas med resten av appen (Androids WebView delar dem) och rensas med sessionen.
- **Meddelanderad** ovanför informationsfälten: `LAUNCHER_MESSAGE` (och `_EN`) eller `LAUNCHER_MESSAGE_URL` (en adress som svarar med ren text, en tom text döljer raden). `LAUNCHER_MESSAGE_STYLE` väljer nivå och färg: blå (`info`), gul (`warning`, standard) eller röd (`alert`). `LAUNCHER_MESSAGE_ICON` väljer ikonen före texten (samma Lucide-ikoner som tjänsterna, standard `info`, `none` för ingen); färgen visar graden.
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
