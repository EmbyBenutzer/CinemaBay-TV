# CinemaBay 0.1.17: Fokus in den Highlights

Aufwärts navigiert gezielt innerhalb der Liste und anschließend über die Monatsauswahl und Filter zur Highlights-Schaltfläche der oberen Navigation. Die automatische geometrische Fokussuche darf den Übergang von den Filtern zur Menüleiste nicht mehr auf das erste Element Suche umleiten.

Kacheln geben ihren aktiven Fokuszustand beim Verlassen frei. Monate und Filter führen einen eigenen Fokuszustand. Der anfängliche Nullwert des Inhalts-Fokusrequests erzwingt keinen Fokuswechsel beim Öffnen der Seite mehr; nur eine ausdrückliche Anforderung aus der oberen Navigation aktiviert den ersten Filter.

Die vorhandene Listenlogik mit drei Spalten, Rückkehr zur ersten Zeile und Monats-/Genre-Filtern bleibt erhalten. Keine Änderungen am Server, an Quellen oder Trailerwiedergabe. Private APK; keine Veröffentlichung ohne Zustimmung.

Die obere Menüleiste navigiert horizontal in der sichtbaren Reihenfolge Suche → Startseite → verfügbare Medienreiter → Für dich → Highlights → Profil. Vor einem Wechsel wird die kurzzeitige Einzelanker-Sperre des vorherigen Fokusübergangs aufgehoben. Dadurch kann der Profilknopf beim Verlassen von Highlights nicht aus der Fokussuche verschwinden.

Beim Eintritt in das Profilmenü darf die kurzfristige Fokuswiederherstellung der Highlights das bereits betretene Menü nicht wieder schließen. Die Anforderung für den ersten Profilmenüeintrag wartet auf einen Frame und wiederholt einen abgewiesenen Versuch begrenzt.
