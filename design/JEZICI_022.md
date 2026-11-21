# Jezici i izvorni paket — 0.22

- Podrazumevani jezik je engleski, nezavisno od sistemskog jezika. Srpski latinicom ostaje dostupan u More / Više → Settings / Podešavanja.
- Izbor se čuva u globalnom `app_language` preference fajlu i ne menja se prelaskom na drugi profil vozila. Activity se ponovo kreira radi native mape; OBD servis se ne zaustavlja pri promeni jezika.
- `LText`, `LIcon`, `LImage`, native map label helpers i offline WebView prevode tekst samo u sloju prikaza. JSON ključevi, OBD zahtevi, jedinice i zapisi merenja ostaju u svom izvornom formatu.
- Katalog `assets/i18n/en.json` obuhvata ekrane, kartice, objašnjenja, mapu, grafikone, povezivanje i prikaz baterijskog paketa. Obrazac `{0}` čuva podatak kao argument; kanonski srpski tekst omogućava prevod ranijih sačuvanih statusnih poruka. Nazivi mesta/stanica, izvorne napomene operatera i tekst u originalnim referentnim slikama ostaju izvorni.
- U arhivu se uključuju Gradle Wrapper, svi Android izvori i resursi sa postojećim offline kartama, alati za pripremu mapa, bezbedan importer punjača i uputstvo. Privatni ključevi, izvozi vožnji, SDK, keševi i radni logovi nisu deo izvornog paketa.

Provera 18.11.2026: `LanguageInstrumentation` — 3/3 grupe prošle (podrazumevani jezik i dinamičke poruke; čuvanje izbora između vozila; očuvanje tehničkih vrednosti i naziva). Nova mapa nije generisana, protokoli vozila nisu menjani. Skripte za mape su originalni alati pripreme; njihove spoljne zavisnosti i izvore treba obezbediti za ponovnu izgradnju.

Na Android emulatoru provereni su prelazak English → Srpski → English, čitljivost konačnog izbora jezika i engleski prikaz baterijskog paketa u offline WebView-u. Završni `assembleDebug` je uspešan. Importer punjača je prošao ciljane provere dodavanja, zamene i odbijanja neispravnih koordinata; izvorni ZIP se pri pakovanju proverava CRC proverom.
