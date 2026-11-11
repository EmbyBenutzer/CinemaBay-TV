# CinemaBay fuer Android TV



Private Anpassung von https://github.com/Silo-Server/silo-android

Basis-Commit: 09f43a2158326afca9864d00788b7019c18fa8fe

Bearbeitet am 10. November 2026.



## Aenderungen



- TV-App-Name: CinemaBay, auch bei anderer App-Sprache.

- Einstellungen: Versionsanzeige, Standard-Kontoname sowie App-Bezeichnungen

  in Hilfetexten fuer Wiedergabe, Profilauswahl, Sprache und Diagnose angepasst.

  Silo-Server, Silo Diagnostics und Herkunftshinweise behalten ihre echten Namen.

- Installations-ID: de.cinemabay.tv. Die internen Kotlin-Namensraeume bleiben erhalten.

- Vereinfachtes weiss-rotes Segel-/Play-Logo als echte SVG-Vektorgrafik mit

  transparentem Hintergrund und Schrift als Pfade in assets/cinemabay.

  Innerhalb der App werden native Android-Vektorgrafiken verwendet: das Symbol

  in der kleinen Navigationsleiste und der Schriftzug auf Anmeldeseiten.

  Der TV-Banner zeigt den neuen Schriftzug. Das App-Icon behaelt auf Wunsch

  das bisherige Piratenschiff: mit neu gezeichneten glatten Kurven statt

  nachgezeichneter Pixelkanten, als transparente SVG mit echten Pfadkonturen

  (CinemaBay-App-Icon.svg), native adaptive Vektorgrafik und klassische PNGs.

  Android-Launcher koennen eine eigene Hintergrundflaeche oder Maske darstellen.

- Lokale Version 0.1.3-cinemabay, TV-versionCode 29.

- Hilfstexte der Einstellungen sind groesser und kontrastreicher;

  ausgewaehlte Menuepunkte sind staerker hervorgehoben.

- Das bereitgestellte CinemaBay_Intro.mp4 (H.264/AAC, 1280x720, ca. 5,27 s)

  laeuft einmal je Kaltstart mit Ton. Warmes Wiederaufnehmen spielt es nicht erneut.

  Bei Fehlern oder spaetestens nach dem Zeitlimit wird der Start fortgesetzt.

- Intro mit OK oder Zurueck ueberspringbar; der vollstaendige Tastendruck

  wird abgefangen und aktiviert keine Aktion im Bildschirm darunter.

  Einstellungen → Allgemein → CinemaBay-Start bietet einen dauerhaften,

  geraetelokalen Schalter. Warmes Wiederaufnehmen zeigt kein neues Intro.

- Fehlende Windows-AAPT2-Pruefsumme ergaenzt, nach Abgleich mit Google Maven.

- Fuer Android Studios Bibliotheksnavigation fehlende Quellen-Pruefsummen

  ergaenzt, nach Abgleich mit den veroeffentlichten Pruefsummen von Google Maven,

  Maven Central und Gradle. Die strikte Abhaengigkeitspruefung bleibt aktiv.



Streaming, Anmeldung, Profile, Fernbedienungssteuerung und Serverprotokolle

verwenden weiterhin den Originalcode. Ein Silo-Server ist erforderlich.

Die App implementiert keine Emby-API. Ein erfolgreicher Build ersetzt keinen

Wiedergabetest auf dem Ziel-TV mit dem eigenen Server.



## Bauen



JDK 21 und Android SDK 36 verwenden. In Android Studio diesen Hauptordner

oeffnen und Gradle synchronisieren. Fuer einen lokalen Build:



    gradlew.bat :androidTvApp:assembleDebug



Die universelle APK entsteht unter androidTvApp/build/outputs/apk/debug/.

Die Update-APK verwendet weiterhin die vorhandene Debug-Signatur.

Die Release-APK ist nicht debuggable, wird mit R8 verkleinert und besitzt

einen eigenen dauerhaften CinemaBay-Signaturschluessel. Sie kann die bisherige

Debug-App nicht direkt aktualisieren; dafuer ist eine bewusste Neuinstallation

noetig. Zukuenftige Release-Updates verwenden denselben privaten Schluessel.

Signaturschluessel und Passwoerter gehoeren nicht in den Quellcode.



## Herkunft und Lizenzdateien



Der Originalcode wird gemaess dem Repository als AGPL-3.0-or-later mit der

zusaetzlichen Erlaubnis in PLATFORM-EXCEPTION.md bereitgestellt.

LICENSE, TRADEMARK.md, PLATFORM-EXCEPTION.md und THIRD_PARTY_NOTICES.md bleiben

erhalten. CinemaBay ist eine private Anpassung und kein offizielles Silo-Produkt.

Das CinemaBay-Logo und das Intro stammen aus den vom Nutzer bereitgestellten Dateien.



Die Android-Handy-App bleibt unter ihrer urspruenglichen Identitaet erhalten;

das CinemaBay-Branding gilt fuer das Modul androidTvApp.



## Update 0.0.3



- Profilmenü: App beenden mit Bestätigung (Abbrechen zuerst). Stoppt den lokalen Player und Wiedergabedienst und entfernt die App-Aufgabe. Anmeldung bleibt erhalten.

- Früheres Piratenschiff als transparentes Vektor-App-Icon wiederhergestellt; ausführliches Logo für die TV-Startkachel. Vereinfachte Zeichen innerhalb der App bleiben erhalten.


## Update 0.0.4

- Einstellungen > Allgemein > Darstellung > Leichtmodus. Standard aus (normale Darstellung); gilt sofort und wird gerätelokal gespeichert, auch nach einem Neustart.
- Leichtmodus: einzelnes Hintergrundbild mit einfachen darüber gezeichneten Verläufen statt separater Grafikfläche mit Masken, animierter Farbtönung und Bildüberblendungen. Hintergrundbilder bis 960 px, Poster bis 256 px; nur das ausgewählte Hero-Bild wird vorgeladen. Poster behalten den Fokusrahmen, ohne Vergrößerung und Fokus-Schatten.
- Wiedergabequalität und Streaming-Einstellungen bleiben vom Darstellungsmodus unabhängig.

## Update 0.0.5

- App-Icon und TV-Kachel zeigen das vollständige Piratenschiff mit CINEMABAY STREAMING aus der neuen Referenz. Als transparente Vektorformen eingebunden; der schwarze Fotohintergrund ist nicht Bestandteil des Icons.
- Oben links in der App steht wieder CinemaBay mit dem schlichten Segel-/Play-Symbol. Leichtmodus und normale Darstellung verwenden denselben Schriftzug.

## Update 0.0.7

Die experimentelle automatische Trailer-Vorschau wurde auf Wunsch wieder vollständig entfernt: keine Vorschau-Komponente, WebView-Einbettung oder zugehörige Einstellungen mehr. Die vorhandenen manuell aufrufbaren Trailer der Original-App bleiben erhalten. Leichtmodus, App beenden und Branding bleiben erhalten.

## Update 0.0.8

Die Profilbildauswahl bietet Netflix, Disney+, Prime Video und HBO/Max mit Serie-/Franchise-Auswahl und Seiten zu je 18 Bildern. Die bisherigen fünf DiceBear-Stile werden nicht mehr angeboten. Bestehende Profilbilder bleiben lesbar und beim Bearbeiten unverändert, bis ein neues Bild ausgewählt wird. Der Katalog enthält 1006 HTTPS-Bildadressen; Herkunft siehe assets/cinemabay/AVATAR-SOURCES.md.

Die Bildwahl wird als stabile, serverkompatible Preset-Kennung gespeichert. CinemaBay TV löst diese Kennung über den eigenen Katalog zum Figurenbild auf. Unveränderte Silo-Clients kennen diesen Zusatzkatalog nicht und zeigen dafür das erzeugte Ersatzsymbol. Bestehende normale und hochgeladene Avatare werden durch den Zusatzkatalog nicht überschrieben.

## Update 0.0.9

Unter Einstellungen → Allgemein → Darstellung lässt sich „Trailer automatisch abspielen“ jederzeit ein- und ausschalten. Die neue Einstellung ist standardmäßig aus und verwendet einen eigenen Schlüssel, damit frühere Trailerexperimente nicht versehentlich wieder aktiviert werden.

Bei aktivierter Vorschau erweitert sich eine ausgewählte Film- oder Serienkachel nach 1,5 Sekunden Verweilen zu einer großen 16:9-Fläche (mindestens 216dp hoch, unabhängig von der Postergröße). Der passende Trailer spielt mit Ton. Die Reihe schafft Platz; die Kopfinformationen werden während der großen Vorschau auf Titel und Metadaten reduziert. Beim Fokuswechsel, Verlassen der Seite, Öffnen des Kontextmenüs oder Ausschalten wird der Player freigegeben.

Nur YouTube-Trailer mit ausdrücklich deutscher Sprachkennung (`de` bzw. regionalen Varianten) werden automatisch verwendet. Englische, unbekannte oder unpassende Einträge werden nicht als Ersatz abgespielt. Offizielle Trailer werden bevorzugt. Gibt es keinen passenden Trailer oder scheitert die Wiedergabe, bleibt das Cover stehen. Untertitel werden beim Start ausgeschaltet; fest in das Video eingebrannte Texte sind davon unabhängig.

YouTube wird erst nach aktivierter Vorschau und Auswahl eines passenden Titels geladen. Die Einbettung übermittelt übliche Videowiedergabedaten an YouTube, keine Silo-Anmeldeinformationen. Die manuell aufrufbaren Trailer und die normale Filmwiedergabe bleiben erhalten. Die native Videofläche liegt außerhalb der Kacheleffekte vor der transparenten Kacheloberfläche, um die auf der Xiaomi-Box beobachtete schwarze Fläche zu vermeiden.


## CinemaBay AI Search 1.0 (11. November 2026)

Die integrierte TV-Suche verwendet einen lokalen Volltextindex, deutsche Suchregeln und kombinierbare Filter. Vollständige Bedienung, Architektur und Grenzen stehen in [CINEMABAY_AI_SEARCH.md](CINEMABAY_AI_SEARCH.md).


## CinemaBay 0.1.1: Serien-Cover

Serien-Cover zeigen unten links die Anzahl verfügbarer Folgen sowie die im aktuellen Profil vollständig gesehenen Folgen. Grundlage sind die vorhandenen Staffel-Daten einschließlich Specials. Fehlen persönliche Daten, erscheint nur die Gesamtzahl. Die Anzeige gilt für Startseiten-Reihen, Katalog, Suche und Bibliotheken; Filme bleiben unverändert. Netzwerkabfragen werden verzögert, beim Verlassen abgebrochen und auf zwei gleichzeitige Anfragen begrenzt.


## CinemaBay 0.1.2: Episoden mit Serien-Cover

Auch Episoden-Kacheln zeigen jetzt die Gesamtzahl und den gesehenen Stand ihrer über series_id zugeordneten Serie. Eine Episode ohne Serienzuordnung bekommt keine erfundene Gesamtzahl. Filmkacheln bleiben ohne Serienanzeige.


## CinemaBay 0.1.3: App-Updates

Automatischer Update-Hinweis auf der Startseite und Update-Seite unter Einstellungen → Allgemein. Stabile GitHub-Releases aus EmbyBenutzer/CinemaBay-TV liefern neue APKs und Versionshinweise. Historische Änderungen bleiben auch ohne Verbindung sichtbar. Download startet nur nach Auswahl, mit Größen-, SHA-256-, Paket-, Mindest-Android-, Versionscode- und Signaturprüfung. FileProvider gibt ausschließlich lesenden Zugriff auf den privaten Update-Ordner. REQUEST_INSTALL_PACKAGES ist deklariert; ab Android 8 erfolgt die Freigabe über die Android-Systemseite. Installation ausschließlich über den bestätigten Systemdialog, ohne stille Installation. Keine Speicher-/Vollzugriffsberechtigung und keine Silo-Serveränderung.
