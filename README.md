# MultiKamera (Android, Kotlin)

Aplikacja łączy kilka telefonów w tej samej sieci Wi-Fi i nagrywa **jeden film z podziałem ekranu** z obrazem ze wszystkich kamer.

## Pobierz

**[⬇ Pobierz MultiKamera.apk (najnowsza wersja)](https://github.com/BekaPeka-GIT/AndroidTestApp/releases/latest/download/MultiKamera.apk)**

Wersja testowa **0.0.1**. Zainstaluj ten sam plik na każdym telefonie (Android 6.0 lub nowszy). Na telefonie otwórz pobrany plik i zezwól na instalację z nieznanych źródeł.

Problemy zgłaszaj w [Issues](https://github.com/BekaPeka-GIT/AndroidTestApp/issues): podaj model telefonu, wersję Androida i co się stało.

## Jak używać

1. Wszystkie telefony podłącz do tej samej sieci Wi-Fi (może to być też hotspot jednego z nich).
2. Na dodatkowych telefonach uruchom aplikację i wybierz **Kamera**. Ekran pokaże podgląd i adres IP.
3. Na głównym telefonie wybierz **Reżyser**. Kamery z sieci łączą się same i pojawiają w podglądzie.
   Jeśli któraś się nie pojawi, użyj **Połącz po IP** i wpisz adres z ekranu kamery.
4. Naciśnij **Nagrywaj**, a potem **Stop**. Film trafia do galerii: `Filmy/MultiKamera/MultiKamera_RRRRMMDD_GGMMSS.mp4`.

Szczegóły:
- Film: MP4 1280×720, H.264, 30 kl./s, dźwięk AAC z mikrofonu Reżysera.
- Układ: 1 kamera na cały ekran, 2 obok siebie, 3–4 w siatce 2×2, więcej w większej siatce. Obraz nie jest przycinany (czarne pasy).
- Reżyser może używać własnej kamery albo ją wyłączyć (wtedy w filmie są tylko zdalne kamery).
- Każdą kamerę można przełączyć na przednią lub tylną.
- Kamery wysyłają podgląd 960×540 (JPEG, ok. 20 kl./s); jakość zdalnego obrazu zależy od Wi-Fi.

## Jak to działa

| Plik | Rola |
|---|---|
| `MainActivity.kt` | wybór roli: Reżyser albo Kamera |
| `CameraActivity.kt`, `CameraLink.kt` | Kamera: serwer TCP, wysyła klatki JPEG do Reżysera |
| `Discovery.kt` | wykrywanie kamer w sieci (mDNS / NSD, usługa `_multikamera._tcp`) |
| `DirectorActivity.kt`, `RemoteCamera.kt` | Reżyser: łączy się z kamerami, odbiera klatki |
| `Compositor.kt` | rysuje podział ekranu (podgląd i film) |
| `SplitScreenRecorder.kt` | koduje obraz (MediaCodec H.264 przez Canvas) i dźwięk (AAC) do MP4 |
| `CameraRig.kt`, `FrameSource.kt` | obsługa kamery telefonu (CameraX) i najnowszych klatek |
| `Gallery.kt` | zapis gotowego filmu w galerii |

- Pakiet: `com.example.multikamera`, minSdk 23 (Android 6.0), targetSdk / compileSdk 35
- Zależności: AndroidX CameraX 1.4, Activity

## Budowanie

```
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
```

APK: `app/build/outputs/apk/debug/app-debug.apk`. Można też otworzyć projekt w Android Studio.

## CI

GitHub Actions (`.github/workflows/android.yml`) buduje debug APK przy każdym pushu na `main` i przy pull requestach (artefakt `app-debug`).
Każdy push na `main` publikuje APK w GitHub Release `v<versionName>` (wersja z `app/build.gradle.kts`). Żeby wydać nową wersję, podnieś `versionName` i `versionCode`.
