# Bestätigte deutsche Synchro-Premieren

`de-synchro.json` ist der eigene, redaktionell gepflegte CinemaBay-Kalender. Die TV-App liest ihn von diesem Repository; neue Termindaten benötigen kein APK-Update. Dieser Feed ist eine **Auswahl belegter Termine**, kein vollständiger Serienkalender. Er aktualisiert oder recherchiert seine Inhalte nicht selbst.

## Aufnahmebedingungen

Nur konkrete neue Folgen mit bestätigtem Termin ihrer deutschen Synchronveröffentlichung in Deutschland aufnehmen. Erforderlich sind Serienidentität, Staffel/Folgennummer, Datum, Anbieter und offizieller Quellenbeleg mit Prüfdatum. Ein deutscher Titel, Untertitel, eine deutsche Tonspur anderer Folgen, Deutschland-Verfügbarkeit oder ein allgemeiner Originaltermin reichen nicht. Keine Wiederholungen und keine aus einem vermuteten Wochenrhythmus berechneten Termine. Deutsche Originalproduktionen sind keine Synchronfassungen und erfüllen diesen speziellen Filter nicht.

Ein offizieller Anbieter kann mehrere Folgen mit einem gemeinsamen Datum ausdrücklich bestätigen; dann erhält jede einzelne Folge diesen belegten Termin. Staffelanzahl und Episodenumfang müssen dabei tatsächlich belegt sein. Eine angekündigte Synchro ohne genauen Start und eine bestätigte Synchro ohne belegte Episodenzuordnung bleiben draußen.

Die App überprüft die Datenstruktur und diese Kennzeichnungen. Sie kann nicht selbst entscheiden, ob der Inhalt einer verlinkten Webseite die redaktionelle Aussage wirklich trägt. Diese Quellenprüfung bleibt Aufgabe der Pflege dieses Feeds.

## Datenfelder

- `schema_version`: derzeit die Zahl `1`.
- `updated_at`: Bearbeitungszeit des Bestands als UTC-Zeitstempel.
- `valid_until`: Ablaufdatum des Bestands, maximal 14 Tage nach `updated_at`. Vor einer Verlängerung Änderungen und Rücknahmen prüfen. Abgelaufene Daten werden ausgeblendet.
- `sources`: eindeutige Quellen-IDs, `kind: official_publisher`, Anbietername, HTTPS-Link, tatsächliches Prüfdatum und knappe eigene Zusammenfassung des Nachweises. Keine vollständigen Artikel übernehmen.
- `series`: stabile eigene Serien-IDs und Titel; optional die öffentliche HTTPS-Bildadresse der Anbieterseite. Keine privaten Silo-IDs oder Zugangsdaten.
- `releases`: pro Folge eine eindeutige ID, `type: episode`, Serien-ID, numerische `season`/`episode`, `german_release_date` als `YYYY-MM-DD`, `audio_language: de`, `audio_format: dubbed`, `region: DE`, `status: confirmed`, `release_kind: german_premiere`, Anbieter, `distribution: streaming` oder `tv` sowie Quellen-ID.

Fehlende Belege, widersprüchliche oder doppelte Episoden, andere Tonsprachen, OmU, bloße Ankündigungen, Filme und Wiederholungen erscheinen nicht. Der Kalender benutzt ausschließlich das bestätigte deutsche Datum. Er übernimmt keine allgemeinen Silo-Ausstrahlungstermine.

## Pflege

1. Offizielle Anbietermeldung prüfen und den belegten Episodenumfang sowie den deutschen Termin festhalten.
2. Quelle, Serie und Episoden in der JSON ergänzen; zurückgezogene Termine entfernen.
3. Prüfzeit und Gültigkeit ehrlich setzen.
4. Im Projektverzeichnis `python scripts/validate-german-calendar.py calendar/de-synchro.json` ausführen.
5. Die geprüfte Datei auf `main` veröffentlichen. Die App liest sie spätestens bei ihrer nächsten Aktualisierung, normalerweise alle sechs Stunden bzw. beim Öffnen; „Aktualisieren“ lädt sofort neu.

Bei einem neuen APK-Build den geprüften Stand zusätzlich nach `androidTvApp/src/androidMain/assets/cinemabay/de-synchro.json` kopieren. Er dient als befristeter Offline-Startbestand. Bei Netzfehlern darf nur ein noch gültiger Bestand mit Offline-Hinweis erscheinen. Ein ausdrücklich ungültiger oder entfernter Feed wird nicht durch Originaltermine ersetzt.

## Geprüfter Startbestand

36 Episodentermine für drei Serien, ausschließlich aus folgenden offiziellen ADN-Belegen:

- **I Parry Everything**, zwölf Folgen am **2. November 2026**. ADN bestätigt die neu verfügbare deutsche Fassung und alle zwölf Folgen. [Anbietermeldung](https://news.animationdigitalnetwork.com/de/2026/11/02/i-parry-everything-jetzt-auf-deutsch-im-stream-bei-adn/)
- **Reincarnated as a Dragon Hatchling**, zwölf Folgen am **22. Oktober 2026**. Der Anbieter nennt ausdrücklich alle zwölf Folgen auf Deutsch ab diesem Datum. [Anbietermeldung](https://news.animationdigitalnetwork.com/de/2026/10/15/reincarnated-as-a-dragon-hatchling-cast-alle-infos-zur-deutschen-synchro/)
- **Kill Blue**, zwölf Folgen gemäß dem expliziten deutschen Episodenplan vom **24. August bis 21. September 2026**. [Anbietermeldung](https://news.animationdigitalnetwork.com/de/2026/08/25/kill-blue-auf-deutsch-bei-adn-cast-und-alle-infos/)

Diese Termine liegen bei Erstellung des Feeds in früheren Wochen. Für die aktuelle oder nächste Woche wurde kein unbelegter Termin ergänzt. Die Datenstruktur ist für weitere Anbieter und normale Serien geeignet und nicht auf Anime begrenzt. Quellen und Bilder bleiben ihren jeweiligen Herausgebern zugeordnet; der Feed enthält keine Videos oder Streamingzugänge.
