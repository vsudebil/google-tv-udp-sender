# WLED TV Audio Sync — nativní aplikace pro Google TV / Android TV

Lehká nativní Kotlin aplikace pro **Google TV / Android TV**, která běží na pozadí jako **Foreground Service**, zachytává interní zvuk přehrávaný v TV bez rootu a odesílá výhradně zvuková (FFT/PCM) data přes UDP do **WLED v0.14+** (protokol WLED Audio Sync v2/v1, port **11988**).

## Předvyplněná konfigurace projektu
- **Metoda zachycení:** Visualizer API (Global Audio Session 0)
- **Cílová IP adresa:** `255.255.255.255`
- **UDP port:** `11988`
- **Protokol:** WLED Audio Sync v2 (00002, 44 bajtů)
- **Výchozí Gain:** `1.30x`
- **Cílová frekvence paketů:** `45 FPS`
- **Autostart po zapnutí TV:** `Zapnuto`

## Varianta A: Sestavení APK v Android Studiu (lokálně)
1. Rozbalte ZIP a otevřete složku `WledTvAudioSync` v **Android Studiu** (JDK 17).
2. Nechte proběhnout Gradle Sync a zvolte **Build → Build Bundle(s) / APK(s) → Build APK(s)** (nebo spusťte `gradle assembleDebug`).
3. Výsledné APK najdete v `app/build/outputs/apk/debug/app-debug.apk`.

## Varianta B: Sestavení APK v cloudu (bez instalace Android Studia)
Součástí projektu je hotový workflow `.github/workflows/build-apk.yml`:
1. Nahrajte rozbalený obsah ZIP do vlastního (klidně soukromého) GitHub repozitáře.
2. V záložce **Actions** se automaticky spustí **Build Google TV APK**.
3. Po ~2 minutách stáhněte hotové `WledTvAudioSync-GoogleTV-APK` (`app-debug.apk`).

## Instalace APK na Google TV
```bash
adb connect <IP_VASI_GOOGLE_TV>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Nebo nahrajte APK přes USB flash disk / aplikaci **Send Files to TV** a nainstalujte přímo v TV.

## Nastavení WLED (ESP32, v0.14+)
1. V rozhraní WLED otevřete **Config → Usermods → AudioReactive**.
2. V sekci **UDP Sound Sync** nastavte **Mode = Receive** a **Port = 11988**.
3. Uložte a restartujte ESP32. Vyberte libovolný efekt označený notou **♪**.
