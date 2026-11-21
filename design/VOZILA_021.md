# Vozila u verziji 0.21

Izbor na početnom ekranu ima 15 profila, pretragu i fotografiju modela/generacije. Svaki profil ima zasebne vožnje, GPS, punjenja, poslednja očitavanja, podešavanja, izvoz i adapter. Promena zaustavlja postojeću vezu pre otvaranja drugog profila. Podaci prvobitnog e-208 ostaju na starim putanjama. Dva primerka istog modela još nemaju odvojene VIN profile.

## Profili

- Peugeot e-208 50 kWh, e-2008 50 kWh; Opel Corsa-e 50 kWh, Mokka-e 50 kWh; Citroën ë-C4 50 kWh; DS 3 Crossback E-Tense 50 kWh.
- Zoe faza 1: Q210 i R240 (22 kWh); Q90, R75, R90 i R110 ZE40 (41 kWh).
- Zoe faza 2: R110 ZE40 (41 kWh), R110 i R135 ZE50 (52 kWh).

R110 ZE40 postoji u dve generacije i zato ima dva odvojena profila. Izbor generacije određuje CAN adresiranje; kapacitet određuje samo označenu početnu pretpostavku plana punjenja. Zamene baterije zahtevaju izbor stvarno ugrađene varijante. Stellantis 54 kWh i drugi protokoli nisu uključeni samo na osnovu sličnog naziva vozila.

## Čitanje Zoe

Čitač je samostalno napisan iz javnih podataka protokola CanZE; izvorni GPL kod i CSV baze nisu ugrađeni u aplikaciju.

- Faza 1: CAN 500 kbit/s, 11-bit; BMS 79B/7BB, EVC 7E4/7EC.
- Faza 2: CAN 500 kbit/s, 29-bit; BMS 18DADBF1/18DAF1DB, EVC 18DADAF1/18DAF1DA. Adapter se postavlja preko ATSP7, ATCP18, ATSH, ATCRA i flow-control komandi.
- Koriste se samo zahtevi za čitanje 21/22. Nema resetovanja BMS-a, pisanja parametara ili otključavanja.

| Podatak | Faza 1 | Faza 2 |
|---|---|---|
| SOC | EVC 222002, ×0,02 | BMS 229002, ×0,01 |
| SOH paketa | EVC 223206 | BMS 229003, ×0,01 |
| Napon paketa | EVC 223203, ×0,5 | BMS 229005, ×0,1 |
| Struja | EVC 223204, (raw−32768)×0,25 | EVC 2221CC, (raw−2000)×0,25 |
| Raspoloživa energija | EVC 22320C, ×0,005 | BMS 2291C8, ×0,001 |
| 96 naponskih grupa | BMS 2141 (62), 2142 (34), mV | BMS 9021–9083, bez 9040/9060/9080, /1024 V |
| 12 temperaturnih sondi | BMS 2104, payload bajt 2 + 3×indeks, −40 °C | BMS 9131–913C, (raw−640)/16 °C |
| Brzina / kilometraža / 12 V | EVC 222003 / 222006 / 222005 | iste komande u EVC faze 2 |
| Spoljna temperatura | nije uključena | EVC 2220DE, (raw−2730)/10 °C |

Znak struje je obrnut u odnosu na CanZE: Battery Lab koristi pozitivno pražnjenje i negativno punjenje/rekuperaciju. Brzina omogućava razdvajanje stacionarnog punjenja od rekuperacije.

CAF1 parser proverava servis, identifikator, kompletnost i redosled višedelnih odgovora. Nedostajući okvir ne pomera numeraciju. Faza 2 čita osam grupa po osvežavanju i objavljuje niz tek kada primi svih 96, unutar dva minuta. Vreme niza je početak ciklusa; to nije istovremeno merenje svih grupa. Na sporom adapteru ciklus može ostati nepotpun. Temperaturne sonde imaju sopstveno vreme merenja. Neodgovorena polja ostaju prazna.

SOH pojedinačnih Zoe grupa, punjački AC/DC status, BMS ukupni brojači, OBC/motor i ostali nepotvrđeni signali nisu izmišljeni iz SOH-a paketa. Evidentirano punjenje može zato imati nepoznatu AC/DC vrstu. Za Zoe se ne koristi Peugeotova naponska SOC kriva. Prikazuje se stvarni SOC ECU-a i srednji napon 96 grupa. Ostali novi modeli dobijaju indeksni pregled bez netačne fizičke šeme e-208.

## Punjenje i domet

Kapacitet se prvo procenjuje iz merenja; dok ih nema, plan punjenja jasno označava nazivni kapacitet izabranog profila kao pretpostavku. Granice procene proširene su za Zoe 22 i 52 kWh. Zoe AC može da se planira i sa 22 kW. Q varijante sa 43 kW za sada imaju konzervativni limit od 22 kW u proračunu. DC kod ZE50 zahteva uključivanje potvrde da automobil zaista ima opcioni CCS. Faza 1 ne dobija DC dopunu u ruti. Model mase/otpora terena ostaje gruba pretpostavka 1.650 kg, eksplicitno navedena u objašnjenju; to nije fabrički model svakog vozila.

## Izvori

- [CanZE faza 1](https://github.com/fesch/CanZE/blob/master/app/src/main/assets/ZOE/_Fields.csv)
- [CanZE faza 2](https://github.com/fesch/CanZE/blob/master/app/src/main/assets/ZOE_Ph2/_Fields.csv)
- [CanZE definicije ECU-a](https://github.com/fesch/CanZE/tree/master/app/src/main/assets)
- [Stellantis e-CMP referenca](https://github.com/EsaHub/EscoEvObdScanner)
- Fotografije: Wikimedia Commons; autor, izvor i licenca svakog dodatog vozila su u `app/src/main/assets/vehicle-photo-credits.json`, dostupni i preko „i“ u izboru vozila. Fotografije predstavljaju generaciju, ne konkretan motor i opcije automobila.

## Provera

ZoeProtocolInstrumentation koristi sintetičke pozitivne, negativne i nepotpune OBD odgovore: dekodiranje obe generacije, struju, svih 96 grupa i 12 sondi, adresiranje, odvojene podatke i zabranu Peugeotove SOC krive za Zoe. Rezultat pokretanja beleži se u `tmp/zoe-v021/protocol-test.txt`. Ovi testovi nisu dokaz povezivanja sa fizičkim Zoe vozilom. Novi profili zahtevaju terensko očitavanje odgovarajućim adapterom; postojeće Peugeot očitavanje nije menjano.

18.11.2026: sve četiri grupe testova prošle su na emulatoru e208_designer. Provereni su pretraga vozila i prelazak sa e-208 na Q210: prikazana je njegova fotografija, bez Peugeotovog poslednjeg očitavanja. Završna korekcija početnog ekrana odvaja tekst od fotografije na prikazu 480×960. Nije pokretan puni regresioni paket mapa/GPS-a niti je potvrđena veza sa fizičkim Zoe vozilom.
