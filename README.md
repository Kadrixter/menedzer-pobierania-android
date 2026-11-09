# Menedżer pobierania (Android)

Prosty menedżer pobierania na Androida, który działa w tle i nie gubi pobrań.

**Pobierz APK:** [Releases](../../releases/latest)

## Co potrafi

- **Dodawanie po linku** – wklej link (lub kilka naraz) albo w przeglądarce: *Udostępnij → Menedżer pobierania*.
- **Działa w tle** – usługa pierwszoplanowa z powiadomieniem o postępie; trzyma Wi-Fi i procesor, żeby pobieranie nie stawało przy wygaszonym ekranie.
- **Pauza / wznawianie** – każdy plik osobno albo wszystkie naraz. Wznawia od miejsca przerwania (HTTP Range), nie od zera.
- **Ponawianie** – przy zerwaniu sieci, timeoucie lub niepełnym pliku ponawia bez końca (przerwy do 60 s), a gdy sieć wróci – od razu. Na koniec sprawdza, czy rozmiar pliku się zgadza.
- **Przeżywa restart** – po zabiciu aplikacji lub restarcie telefonu sama dokańcza pobierania.
- **Sama się wyłącza** – gdy wszystko pobrane, usługa kończy działanie i znika z tła.
- Pliki trafiają do folderu **Pobrane** (Download).

Błędy typu 404/403 są ponawiane 8 razy, potem plik dostaje status „Błąd" i można go wznowić ręcznie.

## Wymagania

Android 10 (API 29) lub nowszy.

Przy pierwszym uruchomieniu warto zezwolić na działanie bez ograniczeń baterii – niektóre telefony (Xiaomi, Motorola, Samsung) agresywnie ubijają aplikacje w tle.

## Budowanie

```
gradlew assembleRelease
```

Wymaga Android SDK 36 i JDK 17+. Bez pliku `keystore/release.keystore` APK zostanie podpisany kluczem debug.
