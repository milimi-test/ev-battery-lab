# Contributing / Doprinos projektu

## English

Report a reproducible issue with the app version, phone/Android version, selected vehicle profile, actual battery variant and adapter/connection type. Describe expected and observed behaviour. Redact VINs, Bluetooth addresses and GPS routes before posting an export publicly.

For a new OBD parameter, document ECU addressing, service/DID, byte order, scaling, units, freshness and a known response. Add a meaningful decoder test and state whether a physical vehicle was tested. Use `null` for missing values. Read-only acquisition is the supported scope.

For chargers, include location, connectors, power if known, source, verification date and explicit pricing status. Use `tools/maps/import_chargers.py`; never mark an unknown price as free. Keep stable IDs. Preserve source/license attributions for all imported data and images.

For changes, follow [the build/customization guide](UPUTSTVO_ZA_DORADU.md). Keep private recordings, keys, build artifacts and local SDK paths out of commits. Analyzer checks: `python -m unittest discover -s analyzer/tests -v`.

## Srpski

Prijavi problem uz verziju aplikacije, telefon/Android, profil vozila, stvarnu bateriju i adapter. Napiši očekivano i dobijeno ponašanje. Pre javnog slanja ukloni VIN, Bluetooth adresu i privatne GPS putanje.

Za novo OBD očitavanje dokumentuj ECU, servis/DID, redosled bajtova, faktor, jedinicu, starost i poznati odgovor. Dodaj test dekodiranja i navedi da li je provereno na vozilu. Nepoznato je `null`; podržano je čitanje podataka.

Za punjače navedi koordinate, priključke, poznatu snagu, izvor i datum. Nepoznata cena nije besplatno punjenje. Koristi alat za uvoz, zadrži ID i atribucije izvora. Prati [uputstvo](UPUTSTVO_ZA_DORADU.md); ne dodaj privatna merenja, ključeve ni lokalne SDK putanje.
