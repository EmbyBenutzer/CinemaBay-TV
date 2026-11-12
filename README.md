# CinemaBay TV

CinemaBay für Android TV, basierend auf [Silo Android](https://github.com/Silo-Server/silo-android). Dies ist eine eigenständige Anpassung und keine offizielle Silo-Veröffentlichung.

## Download

[Aktuelles Release und APK](https://github.com/EmbyBenutzer/CinemaBay-TV/releases/latest)

Android TV ab Android 7.0. Paket-ID: `de.cinemabay.tv`.

## Funktionen

- Film- und Serien-Highlights für den aktuellen und die nächsten drei Monate mit Genre-Filtern, Kurzbeschreibungen und deutschen Vollbild-Trailern. Ersetzt den bisherigen TV-Kalender. Keine vollständige Anbieterübersicht; Termine können sich ändern.
- Trailer-Verfügbarkeit bereits auf der Kachel; automatische Auswahl veröffentlichter 1080p-Dateien mit 720p-Rückfall. Die zugängliche Quelle bestimmt die tatsächliche Auflösung.

- App-Updates mit Startseiten-Hinweis, geprüftem Download, Android-Installation und Versionsverlauf unter Einstellungen → Allgemein.

- CinemaBay-Design mit eigenem App-Icon, TV-Banner und optionalem Startintro.
- CinemaBay AI Search 1.0: lokale Tippfehlersuche, deutsche Suchanfragen und kombinierbare Filter mit Filmplakaten. Kein eingebautes generatives Sprachmodell; optionale externe KI-Schnittstelle vorbereitet und standardmäßig nicht angebunden.
- Wählbarer Leichtmodus und abschaltbare Trailer-Vorschauen.
- Folgenzahl und persönlicher Wiedergabestand auf Serien-Covern, einschließlich Episoden-Kacheln mit zugehöriger Serie.
- Profilbilder und „App beenden“.

Benötigt einen passenden Silo-Server und eine vorhandene Medienbibliothek. Dieses Projekt verändert den Silo-Server nicht und liefert keine Film- oder Serieninhalte mit.

## Aktuelle Version

`0.1.10-cinemabay`, Android-versionCode `43`.

Release-Build und 100 ausgewählte Tests bestanden. Auf einer Xiaomi Android-TV-Box installiert; Trailerbild in 1280 × 720, Rückkehr zur Übersicht und Navigation geprüft. Eine echte 1080p-Wiedergabe wurde mangels zugänglicher Quelle nicht geprüft. Keine vollständige Prüfung aller Funktionen oder Geräte. Die APK verwendet denselben Android-Debug-Signierschlüssel wie die bisherigen CinemaBay-Versionen; private Signierschlüssel werden nicht mitgeliefert.

## In Android Studio öffnen

Das Repository-Verzeichnis mit `settings.gradle.kts` öffnen. Voraussetzungen: JDK 21 und Android SDK 36. Die TV-App liegt im Modul `androidTvApp`.

```powershell
.\gradlew.bat :androidTvApp:assembleDebug
.\gradlew.bat :androidTvApp:testDebugUnitTest --tests "*CinemaBaySearch*" --tests "*TvSearch*" --tests "*CinemaBayEpisodeCountsTest*"
```

Details zur Suche stehen in [CINEMABAY_AI_SEARCH.md](CINEMABAY_AI_SEARCH.md), zur Anpassung in [CINEMABAY.md](CINEMABAY.md). Die Android-Phone-Module stammen aus dem Upstream-Projekt; die CinemaBay-Anpassungen betreffen die TV-App.

## Herkunft und Lizenz

Upstream-Basis: Silo-Server/silo-android, Commit `09f43a2158326afca9864d00788b7019c18fa8fe`. Die vorhandenen AGPL-3.0-Lizenztexte, Plattformausnahme und Hinweise zu Drittanbieterkomponenten bleiben erhalten. Maßgeblich sind die mitgelieferten Lizenzdateien. Das vollständige Quellprojekt liegt zusätzlich bei jedem hier veröffentlichten APK-Release als ZIP bei.

Die ursprünglichen Silo-Automatisierungen sind unter .github/upstream-workflows archiviert und werden in diesem Repository nicht automatisch ausgeführt.
