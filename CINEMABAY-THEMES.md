# CinemaBay: Hauptdesign und Trailer (0.1.12, privat)

Das experimentelle Netflix/Cinema-Theme wurde auf Davids Wunsch entfernt. Es gibt keinen Theme-Schalter und keine alternative Netflix-Startseite mehr. Die bisherigen Farben, Navigation, Startseitenreihen und Serienübersichten bleiben die Grundlage. Eine vollständige Sicherung vor dem Entfernen liegt außerhalb des Projekts in outputs/CinemaBay-vor-Theme-Entfernung-20261117.zip.

Die abschaltbaren Trailer sind Bestandteil des Hauptdesigns. Neue Installationen starten weiterhin ohne automatische Trailer. Bestehende Trailer-Einstellungen bleiben erhalten. Posterreihen können eine ausgewählte Kachel verbreitern; breite Fortsetzen-/Referenzkacheln nutzen ihre vorhandene Bildfläche. Katalograster behalten ihre Spalten und Postergröße; die Vorschau liegt als 16:9-Bild im gewählten Poster. Bei Episoden wird die zugehörige Serie verwendet, sofern eine verlässliche übergeordnete Serien-ID vorliegt. Verdeckte Spoilerbilder, Kontextmenüs, Hintergrundbetrieb und der vorhandene Vorschau-/Leichtmodus werden berücksichtigt.

## Quellenreihenfolge

1. Deutsche YouTube-Trailer aus den vorhandenen Katalogdaten.
2. Deutsche Trailer aus der offiziellen KinoCheck-API, anhand TMDB-/IMDb-ID und Medientyp zugeordnet.
3. Deutsche DF-Trailer aus öffentlich erreichbaren Filmstarts-Seiten. Die Suche gleicht Titel, Originaltitel, Jahr und Film-/Serientyp ab; bezahlte Suchtreffer und fremde Empfehlungen werden ignoriert.
4. Ausdrücklich als OmU bezeichnete Filmstarts-Trailer als Original mit Untertiteln. Explizit englisch untertitelte Fassungen werden ausgeschlossen. Die Highlights bleiben weiterhin deutschsprachig; die OmU-Erweiterung wird nur ausdrücklich für die Kachelvorschau aktiviert.
5. Vorhandene Originalsprach-Trailer bzw. englische KinoCheck-Trailer, wenn die Originalsprache Englisch ist. YouTube wird mit deutscher Untertitelanforderung gestartet. Ohne erkanntes deutsches Untertitelangebot wird der Kandidat verworfen; eine erkannte Untertitelspur ist die Freigabebedingung für das sichtbare Originaltrailerbild.

Keine Quelle deckt jedes Werk ab. Besonders ältere Serien können weder Katalog-Videodaten noch verwertbare Trailer/Untertitel enthalten. Es werden keine fremdsprachigen Trailer ohne Untertitel, falsche Neuverfilmungen oder beliebigen englischen Fassungen als Ersatz behauptet. Vollständige Abdeckung und deutsche Untertitel für jedes Werk lassen sich mit diesen Quellen nicht garantieren.

Die Filmstarts-Anbindung ist eine zusätzliche öffentliche Webseitenquelle, keine garantierte API. Sperren, entfernte Videos und Seitenänderungen bleiben mögliche Ausfälle. Keine Umgehung von Zugriffssperren. KinoCheck begrenzt den freien Zugang laut eigener Dokumentation derzeit auf 1000 Anfragen pro Tag. Metadaten werden begrenzt lokal zwischengespeichert; Videos werden nicht heruntergeladen oder in die APK aufgenommen. Kein zusätzlicher Serverdienst und keine Änderung am Silo-Server.

Quellen: https://api.kinocheck.de/ ; https://www.filmstarts.de/ ; https://developers.google.com/youtube/player_parameters ; https://developers.google.com/youtube/iframe_api_reference

## Verifikation

Build- und Testergebnisse sowie tatsächliche Boxprüfungen werden im Prüfbericht unter outputs dokumentiert. Ein vorheriger Test der neuen Filmstarts-Wiedergabe auf Davids Box zeigte tatsächlich ausgegebene 1280x720-Frames bei The Mongoose; zwei Bildschirmbilder sind gespeichert. Dies ersetzt keinen abschließenden Sichttest der normalen Startseite. Originaltrailer mit sichtbaren deutschen Untertiteln wurden nicht als erfolgreicher Live-Test bestätigt.

## Anschließend: Aussehen anpassen

Der neue Einstellungsreiter wird erst nach gemeinsamer Festlegung seines Umfangs umgesetzt. Kein leerer Schalter und keine eigenmächtige Neugestaltung.
