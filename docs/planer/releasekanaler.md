# Plan (ej genomförd): releasekanaler för PubLiKiosk, pilot och stable

## Context
I dag släpper varje push till `main` en ny release som alla kiosker hämtar vid nästa start (`.github/workflows/build-apk.yml`, `AutoUpdate.java` hämtar `releases/latest`). Det gav åtta versioner på en dag (3.15.0–3.15.8), med rättningar som hade kunnat fångas på testenheter först. Publicom har `pilot` och `stable` som grenar. För PubLiKiosk passar GitHub-releaser bättre eftersom APK:n är ett bygge: en **förhandsversion (prerelease) är pilot**, en **vanlig release är stable**, och samma fil flyttas mellan dem utan ny byggning. Begreppen blir desamma som i publicom, tekniken får skilja.

## Fynd som påverkar planen
- `APP_RELEASE` (katalogen: "latest, eller en release-tagg som v3.7.1") **sparas men läses aldrig**: `ManagedConfig.java:88` skriver `apprelease`, ingen kod använder den. Låsning av version fungerar alltså inte i dag.
- `AutoUpdate.isNewerVersion` jämför bara `x.y.z` och tar bort `v` ur taggen. Ett suffix som `-pilot` skulle inte tolkas, så taggarna ska fortsatt vara `vX.Y.Z`.
- Publicomtools hämtar appens katalog från `main` (`src/lib/catalog-core.ts`, `CATALOG_SOURCES.android`, ändringsbar med `ANDROID_CATALOG_REF`). Med kanaler är `main` för långt före: katalogen bör läsas från senaste stabila taggen.
- Enheter på äldre versioner (≤3.15.8) vet inget om kanaler och hämtar `releases/latest`, som GitHub aldrig sätter på en prerelease. De följer alltså stable utan att något behöver göras.

## Beslut
- **Pilot = prerelease** med taggen `vX.Y.Z` (inget suffix), **stable = samma release med prerelease avstängt** och `latest` satt. Ingen ombyggning vid släpp, exakt den testade filen.
- **`main` bygger** och skapar en prerelease vid push (inte `latest`). Pull requests bygger bara, som nu.
- **Släpp (promote):** ett manuellt steg (`gh release edit vX.Y.Z --prerelease=false --latest`, eller ett workflow med `workflow_dispatch` och taggen som indata). Aldrig automatiskt.
- **Kanal per enhet:** ny nyckel `APP_CHANNEL` (enum `stable`/`pilot`, standard `stable`, `perComputer`). Pilot tar senaste release inklusive förhandsversioner, stable bara vanliga.
- **Låsning:** `APP_RELEASE` implementeras på riktigt (tom eller `latest` = följ kanalen, annars den taggen) eller tas bort ur katalogen. Förslag: implementera, den behövs för återgång.
- **Återgång:** appen uppdaterar bara uppåt. En dålig stable rättas med en ny, högre version (eller med `APP_RELEASE` på enskilda enheter), inte genom att peka om `latest` nedåt.

## Ändringar
1. **`AutoUpdate.java`:** läs kanal och låsning från inställningarna. Stable: `releases/latest`. Pilot: `releases?per_page=10`, första icke-draft (inkluderar prerelease). Låsning: `releases/tags/<tagg>`. Behåll `isNewerVersion` (bara uppåt) och SHA-256-kontrollen.
2. **`ManagedConfig.java`:** läs `APP_CHANNEL`, spara som `appchannel`. Läs `apprelease` där den behövs.
3. **`config/catalog.json`:** `APP_CHANNEL` (grupp teknik, avancerad, `perComputer`) med hjälptext. Rätta hjälptexten för `APP_RELEASE`. `tools/check-catalog.sh` kräver att nyckeln läses i koden.
4. **`.github/workflows/build-apk.yml`:** skapa releasen som prerelease (`prerelease: true` i `softprops/action-gh-release`). Nytt `promote.yml` (`workflow_dispatch`, indata `tag`) som sätter `prerelease=false` och `latest`. Kontrollera att nyckelfilens secrets används som förut.
5. **Dokumentation:** `docs/installera.md` (kanaler, `APP_CHANNEL`, `APP_RELEASE`), README (releaseprocessen: bygg → pilot → släpp).
6. **Publicomtools (PubLiComTools-sessionen):** läs Androidkatalogen från senaste stabila taggen (`releases/latest` → tagg → `contents/config/catalog.json?ref=<tagg>`), med `ANDROID_CATALOG_REF` kvar som åsidosättning (en pilotinstans kan peka på `main`). Visa vilken tagg katalogen kommer från. Nyckeln `APP_CHANNEL` syns då i redigeraren när den släppts stabilt.

## Ordning
1. **Förbered i appen utan att ändra flödet:** punkt 1–3 och 5, släpps som en vanlig release (3.16.0) på dagens sätt. Alla kiosker får den och förstår då kanaler. Kontrollera i publicomtools att enheterna rapporterar 3.16.0.
2. **Byt workflow** (punkt 4) så att nästa push blir en prerelease. Första gången testas hela kedjan med en liten ändring.
3. **Sätt pilotenheterna:** `APP_CHANNEL=pilot` på din fysiska testenhet och emulatorn (de är device owner med release-signaturen, alltså kan de uppdatera sig själva). Övriga kiosker förblir stable.
4. **Publicomtools** (punkt 6), när steg 2–3 fungerar.
5. **Släppregel:** pilotenheterna körs en tid (förslag: minst ett par dagar eller en öppettid) innan `promote`. Dokumentera i README.

## Verifiering
- **Enhetsnära:** på testemulatorn (`PubLiKiosk_Dev`, debug) kan själva uppdateringen inte provas (annan signatur). Prova med de release-signerade enheterna.
- **Pilot:** släpp en prerelease, en pilotenhet hämtar den vid omstart, en stableenhet gör det inte.
- **Släpp:** kör promote, stableenheten hämtar samma version vid nästa omstart. Appens logg (`AutoUpdate: Latest/Current`) visar vad som hämtats.
- **Låsning:** `APP_RELEASE=v3.15.8` på en enhet som kör en nyare version ska inte ge någon nedgradering och inte hämta något nyare.
- **Katalog:** publicomtools visar katalogen från den stabila taggen och hänvisar till den.

## Öppna frågor
- Vilka enheter ska vara pilot (din fysiska enhet och emulatorn, fler)?
- Räcker manuellt släpp, eller ska en prerelease släppas automatiskt efter en viss tid utan problem? (Förslag: manuellt.)
- Ska `APP_RELEASE` implementeras eller tas bort?
- Publicom behåller sina grenar tills vidare. En senare övergång till releaser där är ett eget beslut för PubLiCom-sessionen.
