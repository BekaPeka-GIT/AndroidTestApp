# Hello World (Android, Kotlin)

Minimalna aplikacja Android, która wyświetla na środku ekranu napis **"Hello World!"**.

## Pobierz

**[⬇ Pobierz HelloWorld.apk (najnowsza wersja)](https://github.com/BekaPeka-GIT/AndroidTestApp/releases/latest/download/HelloWorld.apk)**

Wszystkie wersje: [Releases](https://github.com/BekaPeka-GIT/AndroidTestApp/releases). Na telefonie otwórz pobrany plik i zezwól na instalację z nieznanych źródeł.

- Język: Kotlin
- Build: Gradle (Kotlin DSL), Android Gradle Plugin 8.7.3, Gradle 8.11.1 (wrapper)
- Pakiet: `com.example.helloworld`
- minSdk 21 (Android 5.0), targetSdk / compileSdk 35
- Bez zależności od AndroidX: `MainActivity` dziedziczy po `android.app.Activity`

## Struktura

```
app/src/main/AndroidManifest.xml
app/src/main/java/com/example/helloworld/MainActivity.kt
app/src/main/res/values/strings.xml
app/build.gradle.kts
build.gradle.kts, settings.gradle.kts, gradle.properties
gradlew, gradlew.bat, gradle/wrapper/
scripts/build-without-gradle.sh   (awaryjny build bez Gradle, Linux)
```

## Budowanie

### Android Studio
Otwórz folder projektu (File > Open). Studio samo pobierze brakujące SDK. Uruchom przyciskiem Run.

### Z linii poleceń
Wymaga zainstalowanego Android SDK (zmienna `ANDROID_HOME` lub plik `local.properties` z `sdk.dir=...`).

```
./gradlew assembleDebug        # Linux/macOS
gradlew.bat assembleDebug      # Windows
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

### Instalacja na telefonie
```
adb install app/build/outputs/apk/debug/app-debug.apk
```
albo skopiuj plik APK na telefon i otwórz go (trzeba zezwolić na instalację z nieznanych źródeł).

## CI

GitHub Actions (`.github/workflows/android.yml`) buduje debug APK przy każdym pushu na `main` i przy pull requestach. Gotowy plik jest w zakładce Actions, w artefakcie `app-debug`.
Każdy push na `main` publikuje APK w GitHub Release `v<versionName>` (wersja z `app/build.gradle.kts`). Żeby wydać nową wersję, podnieś `versionName` i `versionCode`.
