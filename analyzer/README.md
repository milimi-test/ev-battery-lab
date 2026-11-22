# Battery Lab Analyzer 0.2 — English / Srpski

## English

Download **Battery-Lab-Analyzer-0.2-Windows.exe** from [Releases](https://github.com/milimi-test/ev-battery-lab/releases/tag/analyzer-v0.2). It is a portable Windows desktop program: no Python installation is needed. It is not code-signed. It reads the original measurement ZIPs locally and opens a standalone HTML report in your browser. No server, account, API key or upload is used.

1. Open the program and choose **Add ZIPs**. Select one or multiple Battery Lab exports, including overlapping exports from different dates.
2. Choose a report folder and click **Create report**. Each run creates a new timestamped folder. Original ZIPs remain unchanged.
3. Start with the **combined report**: all global parameters, recorded health changes, SOC/voltage and consumption comparisons. Earlier dates are amber; the latest recorded day is teal. Open **Explore trips** only when needed: filter trips ≥30 km or ≥70 km and select a trip. Charts have aligned panels with physical units and a shared cursor. Click to pin, drag to pan, use +/− to zoom. Overlay uses two real labelled axes for the first two selected signals; other signals remain available in panel mode.
4. Expand **Every recorded SOC & voltage** for every snapshot (100 rows per page), day filtering and a complete CSV download. No raw SOC/voltage rows are discarded for chart size limits.
5. Use **Print / Save PDF** for the report as currently expanded. Global sections print by default; optional trips and raw tables stay closed unless selected. For all raw rows, export CSV rather than printing one table page. The report folder also contains `summary.json`. Reports contain private routes; review before sharing.

### Why it exists

The owner wanted to track voltage and battery health because dashboard SOC seemed inconsistent with observed voltages. **Could displayed 100% correspond to roughly 80% on a reference cell curve?** That question motivated measurement, not a predetermined answer. Other cells' curves cannot establish this car's true SOC or upper/lower buffers.

### Included analysis

- Pack SOH trend, observed SOC span, voltage/current/power, temperatures and recording availability.
- Per-BMS-group minimum/median/maximum voltage, deviation from the simultaneous pack mean, times tied for lowest voltage, and directly recorded group SOH when present. No invented cell capacity or individual SOH.
- All SOC/voltage snapshots, unique time-aligned pairs, 1 pp medians, quartiles and a separate |I|≤5 A subset. Low current does not prove rested OCV; no CATL buffer is inferred.
- Trip energy, coverage, kWh/100 km and effective capacity estimates for sufficiently complete trips with at least 10 percentage points of SOC fall.
- A 10 km/h × five-grade consumption matrix, with distance and segment count per cell; matched head/tailwind comparisons only when enough similar level-road data exists.
- Daily pack/group SOH changes and group voltage shifts under comparable SOC, temperature and load. A BMS estimate change is not proof of physical degradation or recovery.
- Offline route sketch, GPS elevation profile, daily history, recorded charging, detected gaps and linked charger-meter loss estimates.
- Missing climate-control state is explicitly reported; coolant temperatures do not reveal HVAC on/off times.

### Calculation details

Power is integrated by the trapezoidal rule across valid intervals up to 30 seconds. Sign changes are split for positive/negative energy. Missing intervals are excluded, not filled with zero. Trip consumption/capacity requires at least 95% time coverage. Distance uses a plausible odometer difference; otherwise a sufficiently covered integral of fresh speed is used. Speed/environment comparisons require matched energy and distance; speed/temperature bins exclude speeds below 3 km/h.

The matrix uses non-overlapping GPS windows ≥300 m, accuracy ≤30 m, gaps ≤20 s and ≥95% energy/speed coverage. Grade is an altitude regression; windows with absolute grade >25%, residual >10 m or GPS/speed-integral distance disagreement >20% are excluded. Rows use 10 km/h time-weighted mean speed bands; columns are down >3%, down 1–3%, level ±1%, up 1–3%, up >3%. Values are distance-weighted net kWh/100 km, including regeneration. Cells under 5 km or 10 segments are marked limited. GPS altitude is not surveyed elevation.

Wind candidates require grade ±0.5%, altitude residual ≤2.5 m, straightness ≥0.97, speed range ≤6 km/h and ≥4 power/speed intervals. Weather must precede the window by ≤1 h and be within 25 km. Positive headwind = speed × cos(meteorological FROM direction − course). A headwind >5 km/h window is paired once with a tailwind <−5 km/h window: same 10 km/h bin, speed within 2 km/h, grade within 0.25 pp, location within 300 m, heading within 15°, separate trips ≥15 min apart, outside temperature within 3 °C, battery temperature within 5 °C, SOC within 10 pp, same wet/dry/snow class, and wind-component magnitude within 5 km/h. Matching is deterministic with no window reuse. Differences require ≥10 pairs, ≥5 km on each side and ≥2 trips on each side. Otherwise results remain unavailable. Neighbouring segments are not independent experiments; unknown HVAC, tires, traffic and modelled wind prevent causal attribution. An unexpected sign is never forcibly changed.

Voltage/probe arrays must be fresh within 120 seconds; group SOH within 600 seconds. Repeated array timestamps count once. SOC medians pair each unique fresh BMS-extra voltage with the closest SOC/current reading within 15 seconds. The all-readings table retains cached/stale values with their measurement age. Individual group SOH is only directly recorded data, never pack SOH copied into cells. Comparable voltage drift uses the earliest/latest available days with common SOC 5 pp, battery-temperature 5 °C and signed-current 10 A bins, ≥3 scans per bin and ≥10 per period. Insufficient comparisons stay blank. Session records are deduplicated by ID and BMS/GPS samples by vehicle/timestamp. Legacy exports without profile metadata must not mix different cars.

Instant kWh/100 km is 100 × measured kW / fresh km/h, available only at ≥10 km/h. Its median is not trip-average consumption.

### Laboratory references

`references.json` preserves the numeric references already used in the Android app. Interpolation stays within each reference's endpoints; no extrapolation. None is confirmed to be the exact CATL cell in this vehicle.

| Reference | Source and conditions |
|---|---|
| LG M50 / Chen2020 | 101 model voltages, 25 °C, electrode fits and Table VIII stoichiometric bounds. [Chen et al.](https://doi.org/10.1149/1945-7111/ab9050), [pinned PyBaMM implementation](https://github.com/pybamm-team/PyBaMM/blob/v25.10.2/src/pybamm/input/parameters/lithium_ion/Chen2020.py). |
| CALB 58 Ah NMC532 | 26 rested discharge points from Cell_4_2-GITT.csv, chamber 25 °C; test range 60.0468212559 Ah. [Medina et al.](https://arxiv.org/html/2610.23049v1), [dataset CC BY 4.0](https://doi.org/10.5281/zenodo.22301764). |
| Laboratory NMC622 / LP57 | Four C/10 discharge points at 25 °C: 5%=3.36 V, 30%=3.57 V, 80%=3.94 V, 100%=4.18 V. Not rested OCV. [Geuder et al., Table 2](https://www.mdpi.com/2313-0105/10/8/275). |

Charging loss requires a linked charger-meter entry and a completed session with `negative_power_integral` and ≥95% coverage. Missing or inconsistent data leaves loss blank. A capacity estimate derived from SOC fall is not gross/nameplate capacity or a service diagnosis. Tables and charts use UTC.

### Run from source / build the EXE

Python 3.10+ with Tkinter; runtime analysis uses the standard library only:

```powershell
python analyzer/desktop.py
python analyzer/analyze.py export1.zip export2.zip --output my-report
python -m unittest discover -s analyzer/tests -v
```

For Windows packaging, install PyInstaller into your build environment:

```powershell
python -m pip install pyinstaller
./analyzer/build_windows.ps1
```

### Annual use, conditions and cycling

Import the year's overlapping exports together. The report separates vehicles, deduplicates records and shows monthly distance, net driving energy, kWh/100 km, SOH, covered power hours and SOC-derived effective capacity. The year selector filters monthly and descriptive condition tables; matched comparisons explicitly use all imported dates. Months without recordings are not assumed to have zero use. Trips belong to their starting UTC month; integrated discharge is split at month boundaries.

Use **Edit / Uredi** next to the optional conditions path in the desktop app. Select the exact profile, add non-overlapping UTC start/end periods, tire type (`summer`, `winter`, `all-season`, `unknown`), model/size and optionally a known constant HVAC state. End is exclusive. Add multiple periods for a year. Delete a mistaken period and add its replacement. Settings are local in Documents/Battery Lab Reports/conditions.json and do not modify measurement ZIPs. Exported tire-history change events are imported automatically, never backdated from the latest manifest. Unknown tire type or HVAC remains unknown. An explicit recorded HVAC status takes priority over manual HVAC labels. HVAC state is not inferred from coolant temperature; pressure, payload and traffic are not measured here.

Descriptive conditions show eligible GPS-segment totals, not causal effects. Matched winter/summer, wet/dry, snow/dry, HVAC on/off and cold (<5 °C)/moderate (15–25 °C) comparisons require known tire/HVAC state and stable flat windows. Match road vicinity/direction, the same 10 km/h band and speed within 2 km/h, grade within 0.25 pp, SOC within 10 pp, battery temperature within 5 °C and headwind component within 3 km/h. All other condition factors must match; outside temperature within 3 °C except when temperature is the factor. Each result needs ≥10 pairs, ≥5 km and ≥2 trips on each side. No reused control segments. Remaining unmeasured differences prevent causal attribution. Forecast snowfall does not prove snow cover on the road; HVAC comparison is total vehicle energy, not compressor-only energy.

Energy-equivalent cycles = recorded **positive discharge kWh / fixed initial usable kWh**, including energy later recovered by regeneration in the separate incoming-energy column. This is an energy metric, distinct from charge sessions and Ah-based EFC. The profile defaults are 46.3 kWh for supported e-CMP 50 kWh profiles and 22/41/52 kWh for the corresponding Zoe; an unknown profile has no guessed reference. Enter a fixed initial usable reference for the selected profile using **Save kWh only**. Do not replace it with the current degraded capacity: that would inflate later cycle counts. The retained-energy table (100/90/80/70%) is reference arithmetic, not an independently measured SOH. OBD cumulative counter intervals are separate from integrated recording; a decreasing or implausibly jumping counter disables its combined estimate, rather than inventing a reset correction.

Published context is linked directly in the report: [Gailani et al.](https://arxiv.org/abs/2007.01937), [Luh & Blank](https://doi.org/10.1038/s41597-024-03831-x), [NREL cycling-model discussion](https://www.nrel.gov/docs/fy22osti/82648.pdf). Their protocols and chemistries do not yield a universal remaining-cycle prediction for this CATL pack.

The SOC section uses **two distinct charts**, vehicle SOC vs measured voltage and test SOC vs reference voltage. The chosen 4.200 V marker is a comparison ceiling, not a claimed charging limit for every reference. Full/empty endpoints are shown separately per source; NMC622 has no 0% point, so empty voltage stays unavailable. A voltage lookup compares the three reference SOC estimates. Monthly temperature/current tables and near-endpoint observations support investigation of changes; reference headroom is **not a measured vehicle buffer**, and no lower buffer is extrapolated from a 15% minimum. Small current is not proof of rested OCV.

CLI context example (UTC periods must not overlap):

```json
{"reference_kwh":{"legacy-e208":46.3},"periods":[{"vehicle_profile":"legacy-e208","start_utc":"2026-11-01","end_utc":"2027-04-01","tire_type":"winter","tire_label":"Your actual model and size","hvac":"unknown"}]}
```

```powershell
python analyzer/analyze.py export1.zip export2.zip --conditions conditions.json --output my-report
```

## Srpski

Preuzmi **Battery-Lab-Analyzer-0.2-Windows.exe** iz [izdanja 0.2](https://github.com/milimi-test/ev-battery-lab/releases/tag/analyzer-v0.2). Program je prenosiv, ne zahteva instaliran Python i nema digitalni potpis. Izaberi **Dodaj ZIP**, označi jedan ili više izvoza, izaberi folder i klikni **Napravi izveštaj**. Otvara se lokalni HTML izveštaj, a u folderu su i CSV tabele i JSON. Originalni ZIP fajlovi se ne menjaju i ništa se ne šalje na internet.

Prvo dobijaš zbirni izveštaj svih merenja: globalne parametre, stanje baterije, promene očitanog SOH paketa i grupa, SOC/napon, potrošnju i uporedive uslove. Raniji datumi su narandžasti, poslednji dan tirkizan. Sve SOC/napon zapise možeš listati po 100 redova, filtrirati po datumu i preuzeti kao CSV. Medijane koriste jedinstvena sveža merenja uparena sa SOC/strujom do 15 s razlike. Mala struja ne znači da je ćelija dovoljno dugo mirovala.

Program je nastao jer je prikazani SOC delovao nelogično u odnosu na napon. Sumnja da je **100% na tabli približno 80% na referentnoj krivoj** jeste razlog za ispitivanje, ne potvrđen zaključak. LG, CALB i NMC622 reference iz tabele iznad nisu potvrđena CATL kalibracija konkretnog vozila i ne određuju njegove bafere.

Po želji otvori pregled vožnji, izdvoji duže od 30 ili 70 km i biraj signale. Grafikoni imaju poravnato vreme, stvarne jedinice i dovoljno visoke panele. Klik zaključava očitavanje, povlačenje pomera, +/− zumiraju. Preklop koristi dve stvarne obeležene ose. **Štampa / PDF** prati otvorene celine; kompletne sirove podatke preuzmi kao CSV.

Pad ili rast BMS SOH se beleži, ali ne dokazuje sam fizičku degradaciju ili oporavak. SOH paketa se ne kopira u ćelije. Pomeraj napona grupa poredi se samo uz sličan SOC, temperaturu i struju; bez dovoljno uporedivih merenja rezultat ostaje prazan.

Prekidi duži od 30 s ne ulaze u integraciju. Potrošnja cele vožnje i kapacitet traže najmanje 95% pokrivenosti; kapacitet dodatno traži pad SOC-a ≥10 procentnih poena. To je procena energije u odnosu na prikazani SOC, ne bruto kapacitet ni laboratorijski SOH. Podaci ćelijskih grupa koriste sveža, jedinstvena očitavanja. Nepostojeći SOH pojedinačnih grupa se ne izmišlja.

Matrica potrošnje ima korake od 10 km/h i pet klasa nagiba, sa kWh/100 km, kilometražom i brojem segmenata u svakom polju. GPS segmenti su ≥300 m; šumni ili nedovoljno pokriveni se odbacuju. Isprekidana polja imaju manje od 5 km ili 10 segmenata. Negativno znači rekuperaciju, prazno nije nula.

Za vetar se uparuju samo slične ravne deonice pri istoj brzini (razlika do 2 km/h), uz sličan položaj, smer, nagib, temperature, SOC i padavine, bez ponovne upotrebe segmenta. Potrebno je najmanje 10 parova, po 5 km i po dve vožnje za čeoni i leđni vetar. Inače izveštaj kaže da nema dovoljno uporedivih podataka. Rezultat se ne prilagođava očekivanom znaku. Prognoza, klima, saobraćaj i gume ostaju ograničenja. Detaljni pragovi i izvori su u engleskom delu iznad i u izveštaju. Gubitak punjenja zahteva upareno brojilo i kompletno snimanje punjenja; nepoznato stanje klime ostaje nepoznato.

Ponovljeni izvozi se spajaju bez dupliranja, a različiti profili vozila ostaju odvojeni. Stari izvozi bez profila tretiraju se kao e-208; ne mešaj nepovezana stara vozila. Vremena su UTC. Izveštaj sadrži privatnu putanju — proveri ga pre javnog deljenja. Komande za rad iz izvornog koda i izgradnju EXE-a date su u engleskom delu iznad.

### Godišnji pregled, gume, klima i ciklusi

Uvezi sve godišnje snimke zajedno. Mesečni pregled daje kilometražu, energiju, kWh/100 km, SOH i pokrivenost. Godina filtrira mesece i opisne tabele uslova; uparena poređenja koriste sve uvezene datume. Meseci bez snimaka nisu nulta potrošnja.

Dugme **Uredi** pored uslova otvara unos perioda: tačan profil vozila, početak i kraj u UTC, vrsta guma, model/dimenzija i poznato stalno stanje klime. Kraj perioda nije uključen. Periodi se ne preklapaju. Za ispravku obriši period i dodaj novi. Sačuvana istorija guma iz aplikacije čita se od datuma promene nadalje, ne unazad. Nepoznato ostaje `unknown`; klima se ne nagađa iz temperature rashladne tečnosti. Stvarno očitan status klime ima prednost nad ručnim opisom.

Opisne tabele nisu dokaz uticaja jednog faktora. Zimske/letnje gume, kiša/suvo, sneg/suvo, klima uključena/isključena i hladno/umereno porede se tek kad ima dovoljno sličnih ravnih deonica, brzina, smerova, temperatura, SOC-a i vetra. Gume i klima moraju biti poznati; ostali faktori se drže sličnim. Najmanje 10 parova, po 5 km i po dve vožnje po strani. Bez toga nema izmišljenog procenta dodatne potrošnje. Model snega ne dokazuje stanje kolovoza, a klima se poredi kroz ukupnu energiju vozila, ne zasebno kompresor.

Energetski ekvivalentni ciklusi su pozitivna energija pražnjenja podeljena stalnim početnim upotrebljivim kWh. To nije broj priključivanja punjača ni Ah-EFC. Podrazumevana osnova je 46,3 kWh za odgovarajuće e-CMP profile i 22/41/52 kWh za Zoe. Dugmetom **Samo kWh** možeš uneti drugu poznatu početnu osnovu za konkretan profil; ne unositi opadajući trenutni kapacitet. Zbir ECU brojača se ne prikazuje kao pouzdan ako brojač pada ili nelogično skače. Tabela 100/90/80/70% je račun iz referentnog kapaciteta, ne merenje. Istraživanja su navedena sa uslovima, bez univerzalnog obećanja preostalog veka.

SOC vozila i SOC ispitivanja imaju odvojene grafikone. Obeleženo je **4,200 V** kao izabrana uporedna granica i posebno pune/prazne tačke svakog izvora. NMC622 izvor nema tačku 0%, pa se ona ne izmišlja. Promene se pregledaju po mesecu, temperaturi i struji. Referentna razlika nije potvrđen bafer vozila; iz 15% ne može se utvrditi donji bafer. Sve medijane, pojedinačna SOC/napon očitavanja i poređenja izlaze i u CSV.
