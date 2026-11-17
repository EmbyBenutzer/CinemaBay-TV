# Kleine Folgenanzeige (0.1.14, privat)

Serienposter zeigen oben rechts eine kleine weiße Zahl der noch ungesehenen regulären Folgen. Sind alle bekannten regulären Folgen gesehen, erscheint dort ein Haken. Der bisherige zweizeilige Block unten links entfällt. Der separate alte Gesehen-Haken wird auf Serienpostern unterdrückt, damit nicht zwei Anzeigen übereinander liegen; Film-Haken bleiben erhalten. Trailerwiedergabe verdeckt keine Zahlen, weil die Posteranzeige während einer aktiven Vorschau wie bisher ausgeblendet wird.

Die Zählung verwendet reguläre Staffeln mit positiver Staffelnummer. Staffel 0, is_specials-Staffeln und explizite technische Extra-/Trailerstaffeln werden nicht geladen. Für die Zählung werden deren Episodenlisten gelesen: positive Episodennummern, keine eindeutig als Trailer/Teaser/Special/Extra benannten Einträge, keine nachgewiesen nicht verfügbaren Folgen. Unterschiedliche Dateien oder Fassungen derselben Staffel/Folgennummer zählen einmal. Eine gesehene Fassung zählt als gesehen. Begonnene, aber nicht als gesehen markierte Folgen bleiben in der Restzahl.

Gesehen-Zustände stammen aus den Daten des aktuellen Profils. Bei unvollständigen Episodenlisten oder unbekanntem Zustand wird keine erfundene Restzahl und kein vermeintlicher Fertig-Haken angezeigt. Vollständige, konsistente Staffelzusammenfassungen dürfen fehlende Einzelzustände ergänzen, sofern keine ausgeschlossenen Einträge die Zuordnung mehrdeutig machen.

Die vorhandenen Aktualisierungssignale der App und die Rückkehr zur Ansicht lösen eine Prüfung aus; zusätzlich gibt es eine Prüfung im Minutenabstand. Ein kleiner gemeinsamer, nach Betrachtergeneration und Zugriffsrevision getrennter Cache vermeidet mehrfache Abrufe derselben Serie. Unveränderte reguläre Staffeln werden anhand ihrer aktuellen Zähl-/Gesehen-Signatur wiederverwendet; neue Folgen oder geänderte Gesehen-Zahlen lösen einen frischen Episodenabruf aus. Höchstens zwei Serienprüfungen gleichzeitig. Keine Änderung am Server, an der Wiedergabe oder am gespeicherten Gesehen-Verlauf.

Die Regeln können nur vorhandene Metadaten auswerten. Ein in den Quelldaten weder als Extra erkennbarer noch entsprechend benannter Eintrag ist nicht zuverlässig als Trailer identifizierbar. Es werden bewusst keine beliebigen Episodentitel mit dem Wort Trailer ausgefiltert: eine normale Folge namens Trailer Park bleibt eine normale Folge.

Nachweise und tatsächlicher TV-Sichttest stehen im Prüfbericht unter outputs. Kein GitHub-Release ohne Davids Okay.
