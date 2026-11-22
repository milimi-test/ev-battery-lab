# Releases / Izdanja

## Analyzer 0.2 — 2026-11-22

- Report-first layout: combined measurements, global parameters, recorded pack/group SOH changes, optional trips and long-trip filters; earlier/latest dates have different colors.
- Physical-unit signal panels with a shared cursor, pinned readings, and two-axis overlay; speed/power/instant consumption preset.
- Every SOC/voltage snapshot in a paginated table and CSV; time-aligned unique pairs, 1 pp medians, quartiles and three sourced laboratory reference curves.
- Separate vehicle/test SOC charts; 4.200 V comparison marker, per-reference full/empty endpoints, voltage-to-test-SOC lookup and monthly temperature/load comparison. No unverified vehicle buffer claims.
- 10 km/h × five-grade consumption matrix through 130 km/h with distances and evidence counts.
- Monthly/year-filtered reporting, dated tire/HVAC editor, descriptive conditions and matched winter/summer, temperature, rain/snow and HVAC comparisons.
- Explicit FEC terminology and editable partial-cycle calculator (two 25–75% cycles = one nominal FEC), plus recorded trip SOC-depth windows.
- Fixed-reference energy-equivalent cycles, suspicious counter-decrease handling, reference retained-energy table and linked aging research.
- Wind comparisons require one-to-one matching of level road segments, similar speed, location/direction, temperatures, SOC and precipitation, with explicit minimum evidence; insufficient comparisons remain blank.
- English-first/Serbian-second motivation and documentation, report screenshots, portable Windows build script.


## Analyzer 0.1 — 2026-11-21

Portable Windows export analyser with multi-ZIP deduplication, vehicle separation, interactive offline HTML reports, CSV tables, cell/probe summaries, trip coverage, effective capacity estimates, SOC/voltage bins, consumption by speed/temperature/grade/modelled wind, route/elevation sketches and linked charging-meter loss estimates. Eight focused calculation/import tests passed; existing measurement exports and chart interaction were checked locally. Personal test reports are not published.

Prenosivi Windows analizator: više ZIP izvoza, odvojena vozila, lokalni HTML/CSV izveštaji, grafikoni, ćelijske grupe, sonde, vožnje, pokrivenost, procene kapaciteta, SOC/napon, potrošnja po uslovima i punjenja. Osam ciljanih testova prošlo je; postojeća merenja i interaktivni grafikoni provereni su lokalno. Lični probni izveštaji nisu objavljeni.

## Android 0.22 — 2026-11-18

English default with persistent English/Serbian selection across vehicle profiles. Translation covers Compose screens, map labels, explanations and offline pack viewer. Includes source/build guide, Gradle Wrapper, offline map preparation scripts and charger import tool. Three targeted language test groups passed; language switching and pack viewer were inspected in the emulator.

Engleski je podrazumevan, uz trajan izbor engleskog/srpskog za sva vozila. Prevedeni su ekrani, mapa, objašnjenja i prikaz paketa. Uključeni su izvorni kod, uputstvo, Gradle Wrapper i alati za mape i punjače. Tri ciljane grupe provera jezika prošle su; promena jezika i prikaz paketa provereni su u emulatoru.

## Android 0.21 — 2026-11-18

15 vehicle profiles, model photos, separate histories and Zoe phase 1/2 read-only protocol implementations. Four synthetic Zoe test groups passed. Physical Zoe connection remains unverified. Details: [vehicle notes](design/VOZILA_021.md).

15 profila vozila sa slikama, odvojene istorije i čitanje Zoe protokola obe generacije. Četiri grupe sintetičkih testova prošle su; fizička veza sa Zoe još zahteva proveru.

## Earlier development

Offline satellite/terrain maps, streets and contours; charging catalog updates; adjustable range reserve; route charging/elevation planning; multi-signal charts; GPS lifecycle and recording improvements; battery voltage/temperature displays. New vehicle support and estimates require validation against actual vehicle data.

Raniji razvoj: offline satelit/reljef, ulice i izohipse, katalog punjača, promenljiva rezerva dometa, planiranje dopune i visinskog profila, poređenje grafikona, GPS i prikazi baterije.
