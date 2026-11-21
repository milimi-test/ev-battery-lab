# Battery Lab 0.22 — Source code and customization / Izvorni kod i dorada

[English](#english) · [Srpski](#srpski)

## English

The project also includes **Battery Lab Analyzer** for exported measurement ZIPs. Download its portable Windows EXE from [GitHub Releases](https://github.com/milimi-test/ev-battery-lab/releases/latest), or run it from `analyzer/desktop.py`. Its [separate guide](analyzer/README.md) describes reports, calculation methods and packaging.

English is the default language. Select a language under **More → Settings → Language / Jezik**. In Serbian: **Više → Podešavanja**. The language setting applies to all vehicles; their histories remain separate.

### 1. Build an APK from source

Open the extracted project in Android Studio. You need **JDK 17**, Android SDK **35**, and an internet connection for the initial Gradle/Maven dependency downloads. Gradle Wrapper 8.2.1 is included. The full local source bundle includes the maps. The lighter GitHub code ZIP / Git clone distributes large map layers through the APK instead. Download the APK and `SHA256SUMS.txt` from Releases, then restore the maps with Python 3.11+ before building:

```powershell
python tools/restore_offline_assets.py e208-battery-lab-0.22.apk --checksums SHA256SUMS.txt
```

On Windows, run from the project root:

```powershell
# Point these to your installations; Android Studio can locate the SDK automatically.
$env:JAVA_HOME = "C:\path\to\jdk-17"
$env:ANDROID_HOME = "C:\path\to\Android\Sdk"
.\gradlew.bat assembleDebug
```

On Linux/macOS: run `chmod +x gradlew`, then `./gradlew assembleDebug`.
The output is `app/build/outputs/apk/debug/app-debug.apk`.

For a new release, increase `versionCode` and `versionName` in `app/build.gradle.kts`. Installing an update over the existing app requires the same signing key. Android Studio on another computer creates a different debug key; private keys are not included in the archive. Export your measurements from the app before changing the installation.

### 2. Add charging stations

The catalog is `app/src/main/assets/charging-stations.json`, in the `stations` array. Copy `tools/maps/chargers.example.json` to your own JSON file and replace the example with actual station data. IDs starting with `example:` are deliberately rejected to prevent the sample from becoming a fictitious charging station.

A unique `id`, name, coordinates, connectors, source, and verification date are required. Use the connector labels `Type 2`, `CCS2`, and `Šuko` in `sockets`; `kind` is `ac`, `dc`, or `unknown`. Use `null` for unknown power. `fee` can be `free`, `paid`, or `unknown`: only confirmed `free` entries receive a green marker. Do not assume charging is free simply because no price is listed.

```powershell
python tools/maps/import_chargers.py my-chargers.json
python tools/maps/import_chargers.py my-chargers.json --write
# Add --replace when intentionally updating existing IDs.
.\gradlew.bat assembleDebug
```

The first command validates without writing. The second creates a `.bak` backup and updates the catalog. Keep the same ID when changing the coordinates or services of an existing location. Prices and availability are not live data. Adding stations outside the map coverage also requires extending the map.

### 3. Add other maps

The app uses a custom offline format; it does not directly import Google Maps, MBTiles, or arbitrary PNG images. Details and the original preparation scripts are in `tools/maps/README.md`.

| Layer | Files in `app/src/main/assets` | Tool in `tools/maps` |
|---|---|---|
| Satellite tiles and zoom levels | `atlas/*.s2map` | `build_satellite.py` |
| Borders, places, and main roads | `atlas_vectors.atlasdata` | `build_vectors.py` |
| Streets | `streets/*.atlasdata` | `build_streets.py` |
| Road routing | `serbia-roads.graph` | `build_routing.py`, then `compact_routing.py` |
| Elevation and terrain | `route-heights/*`, `relief/*` | `build_route_heights.py`, `build_relief.py` |
| Contour lines | `contours/*` | `build_contours_v019.py` |

For another region, change the input OSM/DEM files and bounds in the scripts, then update **OfflineAtlas.kt**, **OfflineAtlasView.kt**, and **RouteTerrain.kt** accordingly. The current bounds are 17–25° E / 40–48° N; DEM tile indexing also depends on these bounds. `build_vectors.py` additionally contains a Serbia polygon selection specific to the current map. Background imagery, streets, the routing graph, and elevation are separate layers: changing one does not add the others. Preserve source/license attributions and update the metadata.

### 4. Add a vehicle

The code is in `app/src/main/java/rs/battery/lab/`:

- **VehicleProfiles.kt** — ID, name, image, protocol, and capacity. The ID must be unique and stable because it identifies the separate vehicle history. Add the photo to `res/drawable-nodpi` and its source/license to `assets/vehicle-photo-credits.json`.
- **BatteryReader.kt**, **ObdReader.kt**, **ZoeObdReader.kt** — transport and decoding. For another protocol, create a new `BatteryReader` implementation, add an enum value, and select the reader in **ObdService.kt**.
- **BmsReading** in `ObdReader.kt` — the common data format. Unknown values are `null`; missing arrays are empty. Current is positive during discharge and negative during charging/regeneration; power is V×I/1000.
- Adjust `seriesGroups`, `isZoe`, `dcCapable`, the charging planner, and any voltage-based SOC curve. The current `isZoe` logic distinguishes the three existing enum values; **it must be revised when adding another protocol family**. Do not copy addressing, cell counts, nominal capacity, or physical layout from a similar model without confirmation.

A profile using an existing protocol is quick to add; a new name alone does not provide actual OBD support. Verify ECU addresses, PID/DID, response length, byte order, scaling factor, offset, current sign, and reading timestamps. Then test with known responses and on the vehicle itself. Examples are in `ZoeProtocolInstrumentation.kt`; the protocol is documented in `design/VOZILA_021.md`.

### 5. Text and other changes

The UI uses **LText/LIcon/LImage** and `UiStrings` from **AppLanguage.kt**. Serbian strings form the stable source catalog for older messages; English translations and templates are in `assets/i18n/en.json`. For a new string, add a translation pair to `tools/localization/extra-en.json` and run:

```powershell
python tools/localization/compile_catalog.py
```

Numbered argument placeholders remain `{0}`, `{1}`, etc. Do not translate JSON keys, OBD commands, vehicle identifiers, or numbers. `inventory.json` and `en.tsv` form a paired translation snapshot; do not regenerate one without the other. Do not rerun the one-time migrations `wire_ui.py`/`finalize.py`. Place names, addresses, operators, and original reference images retain their original form.

The source includes app code, resources, tests, and tools. The full local ZIP also contains offline maps; for the lighter GitHub code ZIP restore those layers from the APK as described above. Neither package includes personal trip exports, working logs, the SDK, keystores, or build caches. Photos and map data remain subject to their respective licenses; attribution is included in the app and metadata.

---

## Srpski

Projekat sadrži i **Battery Lab Analyzer** za ZIP izvoze merenja. Prenosivi Windows EXE preuzmi iz [GitHub Releases](https://github.com/milimi-test/ev-battery-lab/releases/latest), ili pokreni `analyzer/desktop.py`. [Posebno uputstvo](analyzer/README.md) opisuje izveštaje, računanje i izgradnju programa.

Engleski je podrazumevan. Izbor: **More → Settings → Language / Jezik**. Na srpskom: **Više → Podešavanja**. Izbor je zajednički za sva vozila; njihove istorije ostaju odvojene.

### 1. Napravi APK iz izvornog koda

Otvori raspakovani projekat u Android Studio. Potrebni su **JDK 17**, Android SDK **35** i internet za prvo preuzimanje Gradle/Maven zavisnosti. Gradle Wrapper 8.2.1 je uključen. Potpuni lokalni ZIP sadrži mape. Manji GitHub ZIP koda / Git klon velike slojeve preuzima iz APK-a: preuzmi APK i `SHA256SUMS.txt` iz Releases, pa pre izgradnje vrati mape pomoću Python-a 3.11+:

```powershell
python tools/restore_offline_assets.py e208-battery-lab-0.22.apk --checksums SHA256SUMS.txt
```

Windows, iz korena projekta:

```powershell
# Postavi na svoje instalacije; Android Studio može sam da pronađe SDK.
$env:JAVA_HOME = "C:\putanja\do\jdk-17"
$env:ANDROID_HOME = "C:\putanja\do\Android\Sdk"
.\gradlew.bat assembleDebug
```

Na Linux/macOS: `chmod +x gradlew`, zatim `./gradlew assembleDebug`.
Rezultat je `app/build/outputs/apk/debug/app-debug.apk`.

Za novu verziju povećaj `versionCode` i `versionName` u `app/build.gradle.kts`. Za instalaciju preko postojeće aplikacije potreban je isti potpis. Android Studio na drugom računaru pravi drugi debug ključ; arhiva ne sadrži privatne ključeve. Pre menjanja instalacije izvezi merenja iz aplikacije.

### 2. Dodaj punjače

Katalog je `app/src/main/assets/charging-stations.json`, niz `stations`. Kopiraj `tools/maps/chargers.example.json` u svoj JSON i zameni primer stvarnim podacima. Unos sa ID-jem `example:` namerno se odbija, da primer ne postane lažni punjač.

Obavezni su jedinstveni `id`, naziv, koordinate, priključci, izvor i datum provere. `sockets` koristi oznake `Type 2`, `CCS2` i `Šuko`; `kind` je `ac`, `dc` ili `unknown`. Za nepoznatu snagu upiši `null`. `fee` može biti `free`, `paid` ili `unknown`: samo potvrđeno `free` daje zeleni marker. Ne pretpostavljaj besplatno punjenje samo zato što cena nije navedena.

```powershell
python tools/maps/import_chargers.py moji-punjaci.json
python tools/maps/import_chargers.py moji-punjaci.json --write
# Za namernu izmenu postojećih ID-jeva dodaj --replace.
.\gradlew.bat assembleDebug
```

Prvi poziv samo proverava. Drugi pravi `.bak` i upisuje katalog. Promena koordinata/ponude iste lokacije treba da zadrži njen ID. Cene i zauzetost nisu podaci uživo. Za novu oblast van pokrića potrebno je proširiti i mapu.

### 3. Dodaj druge mape

Ovo je sopstveni offline format, nije direktan uvoz Google Maps, MBTiles ili proizvoljne PNG slike. Detalji i originalne skripte su u `tools/maps/README.md`.

| Sloj | Datoteke u `app/src/main/assets` | Alat u `tools/maps` |
|---|---|---|
| Satelitske pločice i nivoi zuma | `atlas/*.s2map` | `build_satellite.py` |
| Granice, mesta i glavni putevi | `atlas_vectors.atlasdata` | `build_vectors.py` |
| Ulice | `streets/*.atlasdata` | `build_streets.py` |
| Rutiranje putevima | `serbia-roads.graph` | `build_routing.py`, zatim `compact_routing.py` |
| Visine i reljef | `route-heights/*`, `relief/*` | `build_route_heights.py`, `build_relief.py` |
| Izohipse | `contours/*` | `build_contours_v019.py` |

Za drugu oblast promeni ulazne OSM/DEM datoteke i granice u skriptama, zatim uskladi **OfflineAtlas.kt**, **OfflineAtlasView.kt** i **RouteTerrain.kt**. Sadašnje granice su 17–25° E / 40–48° N; indeks DEM pločica takođe zavisi od njih. `build_vectors.py` ima i izbor poligona Srbije specifičan za ovu mapu. Pozadinska slika, ulice, graf rutiranja i visine su odvojeni: promena samo jednog sloja ne dodaje ostale funkcije. Sačuvaj oznake izvora/licenci i ažuriraj metapodatke.

### 4. Dodaj vozilo

Kod je u `app/src/main/java/rs/battery/lab/`:

- **VehicleProfiles.kt** — ID, naziv, slika, protokol i kapacitet. ID mora biti jedinstven i stabilan jer određuje odvojenu istoriju. Fotografiju dodaj u `res/drawable-nodpi`, a izvor/licencu u `assets/vehicle-photo-credits.json`.
- **BatteryReader.kt**, **ObdReader.kt**, **ZoeObdReader.kt** — transport i dekodiranje. Za drugi protokol napravi novu implementaciju `BatteryReader`, dodaj enum i izbor čitača u **ObdService.kt**.
- **BmsReading** u `ObdReader.kt` — zajednički format podataka. Nepoznato je `null`, nedostajući niz je prazan. Struja je pozitivna pri pražnjenju, negativna pri punjenju/rekuperaciji; snaga je V×I/1000.
- Prilagodi `seriesGroups`, `isZoe`, `dcCapable`, plan punjenja i eventualnu naponsku SOC krivu. Trenutni `isZoe` razlikuje postojeće tri enum vrednosti; **mora da se prepravi kada se doda druga porodica protokola**. Ne prepisuj adresiranje, broj ćelija, nazivni kapacitet ili fizički raspored sa sličnog modela bez potvrde.

Profil sa postojećim protokolom dodaje se brzo; nov naziv sam po sebi ne daje stvarnu OBD podršku. Proveri ECU adrese, PID/DID, dužinu, redosled bajtova, faktor, offset, znak struje i vreme očitavanja. Onda uradi test sa poznatim odgovorima i probu na vozilu. Primeri su `ZoeProtocolInstrumentation.kt`; protokol je dokumentovan u `design/VOZILA_021.md`.

### 5. Tekstovi i ostale izmene

Prikaz koristi **LText/LIcon/LImage** i `UiStrings` iz **AppLanguage.kt**. Srpski tekstovi su stabilan polazni katalog za starije poruke; engleski prevodi i obrasci su u `assets/i18n/en.json`. Za novi tekst dodaj par u `tools/localization/extra-en.json` i pokreni:

```powershell
python tools/localization/compile_catalog.py
```

Brojčani argumenti ostaju `{0}`, `{1}` itd. Ne prevodi JSON ključeve, OBD komande, identifikatore vozila ili brojeve. `inventory.json` i `en.tsv` su upareni prevodni snimak; ne regeneriši jedan bez drugog. Jednokratne migracije `wire_ui.py`/`finalize.py` ne treba ponovo pokretati. Nazivi mesta, adrese, operatori i originalne referentne slike ostaju u izvornom obliku.

Izvorni paket sadrži kod aplikacije, resurse, testove i alate. Potpuni lokalni ZIP sadrži i offline mape; za manji GitHub ZIP koda vrati ih iz APK-a kako je opisano iznad. Paketi ne sadrže lične izvoze vožnji, radne logove, SDK, keystore ili build keš. Za korišćene fotografije i kartografske podatke važe njihove licence; njihove atribucije su u aplikaciji i metapodacima.
