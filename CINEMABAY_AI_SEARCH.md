# CinemaBay AI Search 1.0

Die TV-Suche ist in die bestehende CinemaBay-App integriert. Standardmäßig arbeitet sie lokal mit einem deutschen Regelparser, fehlertoleranter Titelsuche und einem SQLite-Volltextindex. Sie enthält kein generatives Sprachmodell und übermittelt Suchanfragen nicht an einen Cloud-KI-Anbieter. Die vorhandene Spracheingabe, Filmplakate, Personenzeile, Sammlungen und Navigation werden weiterverwendet. Hörbücher behalten ihren bisherigen Suchpfad; der neue lokale Suchkatalog umfasst Filme und Serien.

## In Android Studio verwenden

Dieses Verzeichnis mit `settings.gradle.kts` in Android Studio öffnen, die Gradle-Synchronisierung abwarten und `androidTvApp` auswählen. Es werden die bestehenden Projektvoraussetzungen verwendet: JDK 21, Android SDK 36 und der mitgelieferte Gradle-Wrapper. Das bisherige Mindest-Android wird nicht erhöht. Der Quellcode liegt in einem eigenständigen Projektordner; ein bereits in Android Studio geöffnetes anderes Projekt wurde nicht automatisch umgeschaltet.

```powershell
.\gradlew.bat :androidTvApp:assembleDebug
.\gradlew.bat :androidTvApp:testDebugUnitTest --tests "*CinemaBaySearch*" --tests "*TvSearch*"
```

APK-Version: `0.1.3-cinemabay`, Android-versionCode `29`, Paket-ID weiterhin `de.cinemabay.tv`. Die separat gelieferte optimierte Update-APK verwendet denselben Update-Schlüssel wie CinemaBay 0.0.9. Das Projekt enthält keine privaten Signierschlüssel. Ohne konfigurierte Release-Signierung verwendet die bestehende Gradle-Konfiguration den lokalen Debug-Schlüssel.

## Bedienung

Die Lupe in der oberen Navigation öffnet **CinemaBay AI Search 1.0**. Während der Eingabe werden nach einer kurzen Pause Ergebnisse aktualisiert. Die vorhandene Android-TV-Spracheingabe funktioniert weiterhin. Unter **Intelligente Filter** lassen sich mehrere Kriterien kombinieren; Zurück führt von einer Filtergruppe zur Übersicht und anschließend zurück zur Suche. Die Auswahl bleibt auf dem aktuellen Suchbildschirm erhalten und wird nicht profilübergreifend gespeichert.

Beispiele:

- `Hary Poter`, `Matirx` oder `HaryPoter`: tolerierte Tippfehler bzw. fehlende Leerzeichen.
- `ungesehene Thriller ab 2015 unter 120 Minuten mindestens 7,5`
- `Action oder Komödie ohne Horror`
- `Serien aus den 90ern`
- `Filme mit Tom Hanks`
- `Filme auf Deutsch`: Tonspur wird über den vorhandenen Serverfilter geprüft.
- `deutsche Filme`: bezieht sich ausdrücklich auf die Originalsprache.
- `Filme wie Matrix`: Vergleich anhand verfügbarer Genres und Beschreibungswörter, keine neuronale Ähnlichkeitssuche.
- `Filme über Zeitreisen`: Volltextsuche mit kleinen Themen-Synonymgruppen.
- `neueste Serien` oder `beste Thriller`: Sortierung nach Jahr bzw. Bewertung.
- `"Action Jackson" aus 1988`: Anführungszeichen schützen Titelwörter vor der Filtererkennung.

Unterstützte kombinierbare Kriterien: Medientyp, mehrere Genres, ausgeschlossene Genres, Jahr/Zeitraum, Mindestbewertung, maximale Laufzeit, Originalsprache, deutsche Tonspur, ungesehen, Favoriten, Merkliste, 4K, HDR und Altersfreigabe. Bewertungen verwenden IMDb, falls vorhanden, sonst TMDB. Bei Serien ist die gespeicherte Laufzeit in der Regel die typische Folgenlänge. Unbekannte Werte erfüllen keine verlangte Laufzeit-, Qualitäts- oder Freigabebedingung. Freigabefilter erweitern niemals die Server-/Profilrechte.

Manuelle Kriterien verengen die Anfrage. Genre-ODER-Gruppen bleiben als getrennte Gruppen erhalten und werden mit weiteren Kriterien per UND verbunden. Ein ausdrücklich gewählter Medientyp-Chip hat Vorrang vor dem im Suchsatz erkannten Medientyp. Beim Wechsel zurück zu „Alle“ ohne Suchtext und Filter werden frühere Treffer entfernt.

## Was bereits vorhanden war

`TvSearchScreen.kt` und `TvSearchViewModel.kt` enthielten bereits eine Live-Suche mit 300-ms-Verzögerung, Medientyp-Chips, Pagination, Personen- und Sammlungs-Ergänzungen, Spracheingabe und Schutz gegen veraltete Suchantworten. `TvCatalogGrid` rendert weiterhin die Plakate und verwaltet die Fernbedienungsnavigation. `CatalogRepository` bietet bereits alle verwendeten Leseoperationen. Silo-Server-Code oder Serverkonfiguration wurden nicht verändert.

Die Erweiterung verwendet insbesondere die vorhandenen `/api/v2/catalog`-Leseabfragen, `/api/v2/catalog/query` für vorhandene Filter, die vorhandene Berechtigungsprüfung der Personensuche und `source=person` für Filmografien. Maximal 100 Einträge pro Serverseite entsprechen dem tatsächlichen Android-API-Vertrag. Es werden keine neuen Serverendpunkte vorausgesetzt.

## Dateien

Neue Implementierung unter `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/search/`:

- `CinemaBaySearchQuery.kt`: Suchabsicht, Filtergruppen, Normalisierung und deutscher Parser.
- `CinemaBayFuzzySearch.kt`: Levenshtein-BK-Baum und Titelbewertung.
- `CinemaBaySearchIndex.kt`: SQLite-Tabellen, FTS4, sichere parametrisierte Abfragen und Metadatenfilter.
- `CinemaBaySearchRepository.kt`: Katalogaufbau, Live-Suche, Online-Ergänzung, Personen-/Tonspurabgleich und Identitätsgrenzen.
- `CinemaBayAiProvider.kt`: optionale, standardmäßig nicht registrierte HTTPS-Schnittstelle.

Bestehende Integration:

- `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/search/TvSearchViewModel.kt`
- `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/search/TvSearchScreen.kt`
- neu: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/ui/screens/search/CinemaBaySearchControls.kt`
- `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/di/AndroidTvModule.kt`
- `androidTvApp/src/androidMain/res/values/cinemabay_ai_search.xml` und `values-de/cinemabay_ai_search.xml`
- `androidTvApp/build.gradle.kts` und `androidTvApp/gradle.lockfile` für Version und den bereits im Projekt vorhandenen Kotlin-Serialisierungscompiler.

Die neuen Tests liegen unter `androidTvApp/src/androidUnitTest/kotlin/org/siloserver/silo/tv/search/`.

## Index und Verhalten bei großen Bibliotheken

Der Index wird beim ersten Öffnen der Suche für das aktive Profil aufgebaut, in Seiten zu 100 Titeln und mit einer kurzen Pause zwischen den Seiten. Er speichert Metadaten und Plakatadressen, keine Plakatdateien und keine Anmeldedaten. Nur ein aktiver Katalog wird gehalten. Er liegt im privaten Android-App-Cache und kann vom Betriebssystem gelöscht werden.

Der BK-Baum hält nur Titelvokabular. FTS4 liefert Kandidaten, anschließend werden genaue Titel vor Präfixen und Tippfehlern bewertet. Mehrere Wortgruppen werden über SQL geschnitten; optionale erweiterte FTS-AND-Syntax wird nicht vorausgesetzt. SQLite-Abfragen, Indexaufbau und Bewertung laufen außerhalb des UI-Threads. Es werden keine neuen Laufzeitbibliotheken, nativen Datenbank-ABIs, Embedding-Dateien oder Sprachmodelle hinzugefügt. Grundlage sind [Android SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper) und das im System vorhandene [SQLite FTS4](https://www.sqlite.org/fts3.html).

Bis der lokale Katalog fertig ist, ergänzt die vorhandene Onlinesuche erste Ergebnisse. Der Bildschirm zeigt den Aufbau und unvollständige Ergebnisse an. Der lokale Katalog ist auf 25.000 Titel begrenzt. Eine einzelne Trefferliste wird auf 1.200 Kandidaten begrenzt und weist bei Überschreitung auf weiteres Eingrenzen hin. Filmografien sind auf 2.000 und Tonspur-Prüfmengen auf 25.000 Einträge begrenzt. Diese Auswahlmengen werden für maximal 30 Sekunden innerhalb derselben Identität wiederverwendet, um Live-Eingaben nicht ständig dieselbe Abfrage auslösen zu lassen.

Metadaten und persönliche Markierungen bilden einen Katalog-Schnappschuss ab. **Katalog aktualisieren** verwirft ihn und lädt erneut. Nach fünf Minuten wird bei der nächsten Suche ebenfalls ein neuer Schnappschuss begonnen. Eine erste Prüfung der deutschen Tonspur kann bei großen Bibliotheken länger dauern als eine reine lokale Titelsuche.

## Profil- und Rechtewechsel

Der Index ist an Server, Konto, Profil, Zugangsgeneration und Zugriffsrevision gebunden. Die vorhandene `IdentityTransitionBarrier` wird vor Identitätsänderungen benutzt. Bei Profil-/Konto-/Serverwechsel oder gemeldeter Rechteänderung werden Index, Vokabular, Auswahlcache und angezeigte Treffer verworfen. Antworten eines vorherigen Profils dürfen den neuen Index nicht wieder befüllen. Die Personensuche wird nur aufgerufen, wenn der Server ausdrücklich eine nach Medien und Zugriff begrenzte Personensuche unterstützt.

Nach einem Prozessneustart werden alte gespeicherte Zeilen nicht ungeprüft angezeigt; der aktive Katalog wird online neu bestätigt. Innerhalb der laufenden, verifizierten Sitzung kann der fertig aufgebaute Index auch bei einer späteren Verbindungsstörung weiterhelfen. Ein vollständig offlinefähiger Neustart wird aus Gründen der Rechteprüfung nicht zugesagt.

## Optionale KI-Schnittstelle

`CinemaBayAiQueryInterpreter` und `CinemaBayHttpsQueryInterpreter` sind vollständig implementiert. In der gelieferten Koin-Registrierung wird kein Interpreter übergeben; somit entstehen keine Cloud-KI-Anfragen und kein API-Schlüssel wird benötigt.

Wer einen eigenen Dienst ausdrücklich einrichten möchte, kann der bestehenden Factory für `CinemaBaySearchRepository` in `AndroidTvModule.kt` als sechsten Konstruktorparameter eine konfigurierte `CinemaBayHttpsQueryInterpreter`-Instanz übergeben. Der Dienst muss separat existieren; diese Erweiterung installiert keinen KI-Server und ändert Silo-Server nicht. Ein eigener Dienst und sein Schlüssel sind keine Bestandteile der APK.

Der Adapter akzeptiert ausschließlich HTTPS, folgt keinen Weiterleitungen, begrenzt Antwortgröße und Verbindungszeiten und nutzt nie den authentifizierten Silo-HTTP-Client. Er sendet nur den begrenzten Suchtext. Antwortdaten dürfen Suchabsichten liefern, niemals Filmtreffer oder Zugriffsrechte. Ungültige Antworten und Verbindungsfehler fallen auf den lokalen Parser zurück.

Anfragevertrag:

```json
{"version":1,"language":"de","query":"ungesehene Thriller ab 2015"}
```

Beispiel einer gültigen Antwort:

```json
{"text":"","mediaType":"movie","filters":{"genres":["Thriller"],"yearMin":2015,"unwatched":true}}
```

Weitere unterstützte Felder entsprechen exakt den serialisierbaren Datenklassen in `CinemaBaySearchQuery.kt`; unbekannte Antwortfelder werden abgelehnt. Der Adapter wurde ohne konfigurierten externen Dienst vorbereitet. Ein echter Cloud-Ende-zu-Ende-Test ist damit ausdrücklich nicht erfolgt.

## Validierung

Automatisierte Tests prüfen Parser, tatsächliche SQLite-Abfragen unter Robolectric, Rangfolge, Genregruppen, Grenzen zwischen Profilen, verspätete Antworten, Rechteänderungen und den integrierten ViewModel-Pfad. Zusätzlich werden die vorhandenen Suchtests ausgeführt. Die genaue Zahl und die finalen Build-Ergebnisse stehen im mitgelieferten Testbericht. Windows-/Robolectric-Tests sind kein physischer Android-TV-Test und keine Messung der Geschwindigkeit einer bestimmten Box. Phone, Tablet und Foldable sind von dieser TV-Modul-Erweiterung nicht betroffen.

Ein Unterschiedspatch gegen CinemaBay 0.0.9 und das vollständige Projekt werden zusätzlich als Dateien bereitgestellt. Die originale AGPL-Lizenz samt Plattform-Ausnahme und den bisherigen Hinweisen bleibt enthalten.
