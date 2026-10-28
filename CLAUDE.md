# Artemide Duo (Android)
Fork von Artemide/Moonlight für das Microsoft Surface Duo: Stream oben, Tastatur/Trackpad unten. Branch: `duo`. Version in `DUO_VERSION`.

## Arbeitsregeln (vom Nutzer)
- Antworten auf Deutsch, locker, kurz.
- Feedback des Nutzers sofort in `FEEDBACK.md` eintragen – NICHT sofort umsetzen/bauen.
- Gebaut wird nur, wenn der Nutzer "build" sagt. Dann: ALLE offenen Punkte aus `FEEDBACK.md` umsetzen, Version erhöhen, committen, pushen, CI prüfen bis grün, danach `FEEDBACK.md` leeren (unter "Letzter Build" Version eintragen) und kurz auf Deutsch berichten.
- Niemals IP-Adressen oder Hostnamen des Nutzers in Code, Commits, Screenshots oder Texte schreiben (Platzhalter: "Gaming-PC", "LAN").
- Code lesen statt raten; nur die Dateien öffnen, die für die Aufgabe nötig sind.

## Build
- Lokal: `./gradlew assembleNonRoot_gameDuo` (CI baut zusätzlich `assembleNonRoot_gameDuoPlus`).
- CI: `.github/workflows` baut bei Push auf `duo` und hängt die APKs an ein Prerelease.
- Duo-Code liegt unter `app/src/main/java/com/limelight/duo/`.

## Fachliches
- Tastaturen: Keyboard 1 = Normal, 2 = Wide, 3 = Slim, 4 = Split.
- Split: Anordnung wie iPhone Duo (nur G/V doppelt), Tastengröße wie Slim, Hälften links/rechts bündig, ganze Fläche Trackpad mit Gesten-Hinweis.
- Die iOS-Version (gersiaaan/Artemide-Duo-for-iOS) ist ein 1:1-Port dieser App.
