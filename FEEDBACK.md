# Feedback Android

## Offen
- Stream im Hintergrund offen lassen: Wenn Artemide geschlossen bzw. in den Hintergrund geschickt oder eine andere App geöffnet wird, soll der Stream nicht beendet werden (heute stoppt Game.onStop() die Verbindung). Zurück in Artemide → Stream läuft direkt weiter. Hinweis: braucht Vordergrund-Dienst mit Benachrichtigung (auch fürs Mikrofon im Hintergrund) und Neuaufbau des Video-Decoders beim Zurückkehren.

## Letzter Build
- v0.39
