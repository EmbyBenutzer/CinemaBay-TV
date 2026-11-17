# CinemaBay 0.1.15: Suchspeicher und TV-Navigation

Der SQLite-FTS4-Index liegt im privaten, nicht gesicherten App-Speicher (noBackupFilesDir). Android darf ihn nicht wie temporäre Cache-Dateien bei Speicherknappheit entfernen. Neuinstallation oder Löschen der App-Daten erfordert einen neuen Aufbau. Keine neue Bibliothek und keine enthaltenen Posterdateien.

Beim Öffnen wird ein vollständiger Snapshot desselben Servers, Kontos und Profils wiederverwendet. Die Besitzerkennung ist ein SHA256-Hash; keine Zugangsdaten werden gespeichert. Nach sechs Stunden erfolgt beim nächsten Suchzugriff eine Hintergrundaktualisierung. Vorhandene Treffer bleiben währenddessen verfügbar. Nach erfolgreichem Komplettabruf werden entfernte Titel bereinigt. Unterbrochene Erstabrufe werden nicht als vollständig gespeichert. Katalog aktualisieren erzwingt einen neuen Abruf. Profil-, Konto-, Server- und Zugriffsänderungen verwerfen den bisherigen Snapshot.

Beim erneuten Öffnen der Datenbank wird das Tippfehler-Wörterbuch aus den gespeicherten normalisierten Titeln geladen. Der Katalog selbst wird dazu nicht vom Server geladen.

Beim Fokus auf Suchfeld oder Filter wird der obere Bereich vollständig zurückgeholt. Alle Kacheln der ersten Ergebniszeile bekommen einen expliziten Weg nach oben zu Personen oder Filtern. Andere Ansichten behalten ihre bisherigen Vorgaben.

Die automatisierten Prüfungen decken die Wiederverwendung ohne Netzabruf, Datenbank-Neuöffnung mit Tippfehlersuche, Bereinigung gelöschter Titel, Profiltrennung und verspätete Antworten ab. Tatsächliche Box-Prüfungen stehen im separaten Prüfbericht.

Kein Silo-Server geändert. Kein Release ohne Davids Zustimmung.
