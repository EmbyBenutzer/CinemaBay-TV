# CinemaBay 0.1.10 – Highlights (private Vorschau)

Die Android-TV-Kalenderoberfläche einschließlich des Deutschland-/Favoritenabgleichs und des optionalen Kalender-API-Zugangs wurde entfernt. Der interne Navigationspfad bleibt für gespeicherte Navigation kompatibel, zeigt jedoch ausschließlich die neue Highlights-Ansicht. Die Serienabteilung und der Silo-Server werden nicht verändert. Die gemeinsame Kalendertechnik für die separate Handy-App bleibt bestehen.

## Bedienung

Unter „Highlights“ stehen kommende Deutschland-Starts von Filmen (Kino) und Serien (Sender/Streaming). Vier Monatsauswahlen umfassen den aktuellen und die nächsten drei Monate. „Alle“, „Filme“ und „Serien“ lassen sich mit einem Genre kombinieren. „Genre: Alle“ öffnet die Genre-Auswahl. Unter Anbieter und Starttermin steht rechts neben dem Cover ein kurzer Inhaltstext. Fehlende Genres werden als unbekannt behandelt; fehlende Beschreibungen werden ausdrücklich angezeigt. Die vollständigen Genre-Angaben und Beschreibungen des gewählten Monats werden im Hintergrund ergänzt, da Filmvorschauen ihre Genre-Liste teilweise kürzen. Die Vorschau erscheint vorher bereits. Bei nicht erreichbaren Angaben oder Ablauf des 45-Sekunden-Ladebudgets erscheint ein Hinweis; fehlende Genre-Zuordnungen bleiben unbekannt. Die Ergebnisse werden zwischengespeichert. Diese Vorschau ist unabhängig von eigener Mediathek und Favoriten. Sie verspricht keine vollständige Liste aller Anbieter. Termine können sich ändern; angekündigte Monatsangaben ohne konkreten Tag werden nicht in erfundene Tagesdaten umgewandelt.

„Hier Trailer anschauen“ lädt erst nach dem Klick den zugehörigen deutschen Trailer. Das Video läuft im nativen Vollbildplayer mit Ton; zuschaltbare Untertitel sind ausgeschaltet. Bereits in die Videodatei eingebrannte Schrift kann die App nicht entfernen. Am tatsächlichen Ende wird der Player geschlossen; Übersicht, Monat, Filter und Position bleiben erhalten. Zurück beendet die Wiedergabe ebenfalls. Ein nicht verfügbarer Trailer führt zu einer verständlichen Meldung mit Rückkehr zur Übersicht.

## Daten und Wiedergabe

Öffentliche Vorschauen: https://www.filmstarts.de/filme-vorschau/de/ und https://www.serienjunkies.de/docs/serienplaner.html. Die App liest die Vorschauseiten direkt ohne Silo-Anmeldedaten, Bibliotheksabgleich oder Kalender-API-Schlüssel. Bis zu drei öffentliche Abrufe laufen gleichzeitig. Verarbeitung und HTML-Parsing erfolgen außerhalb der Oberfläche; doppelte Abrufe derselben Seite werden zusammengeführt. Webseiten werden sechs Stunden zwischengespeichert; bei Netzausfällen ist ein bis zu 36 Stunden alter Zwischenspeicher ausdrücklich gekennzeichnet. Teilweise fehlgeschlagene Abrufe werden als Hinweis angezeigt.

Es wird kein Browser-, YouTube- oder Webseiten-Player eingebettet. Stattdessen spielt Media3/ExoPlayer die von den Quellen veröffentlichten MP4-Dateien. Ein positiver Netzwerkfilter erlaubt nur HTTPS-Videodateien der geprüften Video-Hosts; Weiterleitungen und weitere Hosts werden abgelehnt. Dadurch werden keine Werbeskripte oder separaten Werbeclips geladen. Werbung, die ein Anbieter fest in eine Videodatei schneidet, kann ein Netzwerkfilter nicht entfernen.

Die Trailer-Zuordnung verwendet nur explizite Links desselben Filmtitels bzw. Serienpfads. Deutsche Fassung wird geprüft; englische/OmU-Videos werden nicht stillschweigend verwendet. Fehlt eine erreichbare deutsche Datei, bleibt der Trailer unverfügbar. Ein Serientrailer kann die Serie allgemein vorstellen und muss nicht die neu angekündigte Staffel zeigen. Kein automatischer Trailerstart in der Übersicht, kein dauerhaft laufender Hintergrundplayer. Bei App-Wechsel pausiert der Player; bei Schließen werden Decoder und Audiofokus freigegeben.

## Entwicklung

Implementierung: `androidTvApp/src/androidMain/kotlin/org/siloserver/silo/tv/highlights/`.
Der TV-Player verwendet eine TextureView; im Gerätetest konnten dadurch tatsächliche Trailerbilder im Bildschirmabzug geprüft werden. Die vorhandenen Media3-, Coil- und Netzwerkbibliotheken werden weiterverwendet. Jsoup ist der einzige zusätzliche direkte TV-Baustein und im bestehenden Versionskatalog festgeschrieben. Gradle-Lockdatei und Prüfsummen gehören zum Projekt. Veröffentlichung nur nach ausdrücklichem OK.

## Scrollkorrektur 0.1.9

Beim Navigieren mit der Fernbedienung wird die vollständige fokussierte Kachel sichtbar gehalten. Vertikale Schritte wählen ausdrücklich die entsprechende Kachel der Nachbarzeile. Das Raster wird zuerst positioniert und der Fokus danach gesetzt, damit die gewählte Spalte auch bei erst später aufgebauten Kacheln erhalten bleibt. In der ersten Zeile wird der tatsächliche Listenanfang wiederhergestellt. Der Fokus auf dem Trailer-Link darf das Cover und den Titel darüber nicht verdecken. Beim Wechsel zu Monatsauswahl oder Filtern wird das Raster wieder auf Zeile null ohne Restversatz gestellt. Filter, Auswahl und Trailerverhalten bleiben erhalten.

## Trailer-Verfügbarkeit 0.1.9

Für sichtbare Kacheln wird vor einem Klick geprüft, ob die angebundenen Quellen einen direkt abspielbaren deutschen Trailer bereitstellen. Währenddessen steht unten „Trailer wird geprüft …“. Bei einem passenden deutschen Video erscheint „Hier Trailer anschauen“, ansonsten „Kein deutscher Trailer verfügbar“. Temporäre Netzwerkfehler erhalten eine eigene Meldung mit erneuter Prüfung. Nicht verfügbare Trailer öffnen keinen Player; die Kachel bleibt für die Navigation erreichbar. Die Prüfung lädt nur Metadaten und startet kein Video. Ergebnisse werden innerhalb der Ansicht wiederverwendet; „Aktualisieren“ prüft erneut. Metadaten werden in kleinen Paketen geladen, damit die Vorprüfung sichtbarer Trailer zügig an die Reihe kommt.

## Automatische Trailerqualität 0.1.10

Die App wählt aus den ausdrücklich veröffentlichten, freigegebenen Direktdateien desselben deutschen Trailers automatisch 1080p vor 720p. Varianten werden aus VideoObject-/Encoding-Angaben, passenden Player-Quelldaten und Video-Source-Elementen gelesen; Datei-Adressen werden nicht erraten. Fehlt eine erreichbare Full-HD-Variante, bleibt die vorhandene HD-Datei in Verwendung. Für einen gewählten Full-HD-Trailer wird eine veröffentlichte 720p-Ersatzdatei mitgeführt. Bei Wiedergabefehler oder anhaltendem anfänglichem Puffern wird diese einmalig versucht, möglichst an derselben Wiedergabeposition.

Anbieter können 1080p in einem externen Webplayer anbieten, den direkten Abruf aber sperren. Beim untersuchten Dailymotion-Trailer wurde 1080p angekündigt, der Stream antwortete auf Windows und der Xiaomi-Box jedoch mit HTTP 403. Diese gesperrte Quelle wird nicht eingebaut; die bisherige frei abrufbare 720p-Datei bleibt verfügbar. Full-HD-Auswahl und Rückfallregeln werden durch gezielte Tests geprüft; eine echte 1080p-Wiedergabe darf erst nach einem entsprechenden Gerätetest behauptet werden.
