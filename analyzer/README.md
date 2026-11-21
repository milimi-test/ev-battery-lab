# Battery Lab Analyzer 0.1 — English / Srpski

## English

Download **Battery-Lab-Analyzer-0.1-Windows.exe** from the repository's [Releases](https://github.com/milimi-test/ev-battery-lab/releases/latest). It is a portable Windows desktop program: no Python installation is needed. It is not code-signed. It reads the original measurement ZIPs locally and opens a standalone HTML report in your browser. No server, account, API key or upload is used.

1. Open the program and choose **Add ZIPs**. Select one or multiple Battery Lab exports, including overlapping exports from different dates.
2. Choose a report folder and click **Create report**. Each run creates a new timestamped folder. Original ZIPs remain unchanged.
3. Select a vehicle in the report. Choose signals and a trip/time window; drag the chart to pan, use +/− to zoom, and move the cursor for actual values. Separate axes are the default; overlay uses an explicitly normalized range per signal.
4. Use **Print / Save PDF** for a printable copy. The report folder also contains CSV tables and `summary.json`. Share reports deliberately: they contain your recorded route.

### Included analysis

- Pack SOH trend, observed SOC span, voltage/current/power, temperatures and recording availability.
- Per-BMS-group minimum/median/maximum voltage, deviation from the simultaneous pack mean, times tied for lowest voltage, and directly recorded group SOH when present. No invented cell capacity or individual SOH.
- SOC/mean-voltage bins with a separate |I|≤5 A subset. Low current does not prove rested OCV; no CATL buffer is inferred.
- Trip energy, coverage, kWh/100 km and effective capacity estimates for sufficiently complete trips with at least 10 percentage points of SOC fall.
- Consumption by speed and outside temperature; grade and projected head/tailwind when GPS/weather coverage permits. These are descriptive comparisons, not causal isolation of weather effects.
- Offline route sketch, GPS elevation profile, daily history, recorded charging, detected gaps and linked charger-meter loss estimates.
- Missing climate-control state is explicitly reported; coolant temperatures do not reveal HVAC on/off times.

### Calculation details

Power is integrated by the trapezoidal rule across valid intervals up to 30 seconds. Sign changes are split for positive/negative energy. Missing intervals are excluded, not filled with zero. Trip consumption/capacity requires at least 95% time coverage. Distance uses a plausible odometer difference; otherwise a sufficiently covered integral of fresh speed is used. Speed/environment comparisons require matched energy and distance; speed/temperature bins exclude speeds below 3 km/h.

GPS grade uses segments of at least 200 m, fixes with reported accuracy ≤30 m and gaps ≤20 s. GPS altitude is not surveyed elevation; short noisy slopes can remain. Weather is matched only from a preceding observation no older than one hour and within 50 km. Wind is projected onto travel direction; positive values are headwind. Environmental bins need ≥95% energy/speed coverage of the GPS segment. Bins under 5 km are marked as limited evidence.

Voltage/probe arrays must be fresh within 120 seconds; group SOH within 600 seconds. Repeated array timestamps count once. Voltage bins also deduplicate the BMS-extra timestamp. Session records are deduplicated by ID and BMS/GPS samples by vehicle and timestamp. Older exports without a vehicle profile are explicitly grouped as legacy e-208; do not mix unrelated legacy vehicles.

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
python -m PyInstaller --noconfirm --onefile --windowed --name Battery-Lab-Analyzer-0.1-Windows --add-data "analyzer/report.html;." --distpath analyzer/dist --workpath analyzer/build analyzer/desktop.py
```

## Srpski

Preuzmi **Battery-Lab-Analyzer-0.1-Windows.exe** iz [Releases](https://github.com/milimi-test/ev-battery-lab/releases/latest). Program je prenosiv, ne zahteva instaliran Python i nema digitalni potpis. Izaberi **Dodaj ZIP**, označi jedan ili više izvoza, izaberi folder i klikni **Napravi izveštaj**. Otvara se lokalni HTML izveštaj, a u folderu su i CSV tabele i JSON. Originalni ZIP fajlovi se ne menjaju i ništa se ne šalje na internet.

Dobijaš pregled baterije, svih dostupnih BMS grupa/sondi, SOC-a i napona, vožnji, potrošnje prema brzini i temperaturi, nagibu i vetru, GPS skicu, visinski profil, dnevnu istoriju i punjenja. Biraj vozilo, vožnju i signale na grafiku. Povlačenje pomera vreme, +/− zumiraju, a kursor prikazuje stvarne vrednosti. Preklop je označena relativna skala. **Štampa / PDF** pravi kopiju za deljenje.

Prekidi duži od 30 s ne ulaze u integraciju. Potrošnja cele vožnje i kapacitet traže najmanje 95% pokrivenosti; kapacitet dodatno traži pad SOC-a ≥10 procentnih poena. To je procena energije u odnosu na prikazani SOC, ne bruto kapacitet ni laboratorijski SOH. Podaci ćelijskih grupa koriste sveža, jedinstvena očitavanja. Nepostojeći SOH pojedinačnih grupa se ne izmišlja.

Nagib se računa iz GPS visine na segmentima ≥200 m uz tačnost GPS-a ≤30 m; visina može biti šumna. Vetar je iz prethodnog modelskog zapisa do jednog sata i do 50 km udaljenosti, projektovan prema pravcu kretanja. Poređenja nisu dokaz izolovanog uticaja vetra/nagiba, a grupe kraće od 5 km označene su kao ograničene. Gubitak punjenja zahteva upareno brojilo punjača i kompletno snimanje punjenja. Ako stanje klime nije snimljeno, izveštaj to jasno kaže.

Ponovljeni izvozi se spajaju bez dupliranja, a različiti profili vozila ostaju odvojeni. Stari izvozi bez profila tretiraju se kao e-208; ne mešaj nepovezana stara vozila. Vremena su UTC. Izveštaj sadrži privatnu putanju — proveri ga pre javnog deljenja. Komande za rad iz izvornog koda i izgradnju EXE-a date su u engleskom delu iznad.
