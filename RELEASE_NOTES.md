## Artemide Duo v0.27

### Two APKs from now on
| File | What it is |
|---|---|
| `ArtemideDuo-v0.27-arm64-v8a.apk` | **Normal**: built-in word list, next-word prediction that learns your phrases. |
| `ArtemideDuo-v0.27-Plus-arm64-v8a.apk` | **Plus**: everything from Normal plus the AOSP LatinIME suggestion engine (the engine behind the AOSP keyboard / HeliBoard) with full German and English dictionaries and a personal history dictionary. About 5 MB bigger. |

Both are the same app: installing one over the other keeps all settings, so you can switch any time.

### Keyboard
- **Plus:** corrections use where your finger landed relative to the neighbouring keys, the dictionaries include word pairs (context and next-word prediction) and a personal history learns your words and phrases and slowly forgets ones you stop using. Missing umlauts are fixed automatically (uber → über).
- Next-word prediction after a space, also in the normal build (learns from what you type).
- Word, space, backspace: the whole previous word is picked up again, so suggestions see all of it.
- Compact mode: the keyboard half works as a trackpad too.
- Ten-finger typing in Wide mode: no more lost keys (e.g. the space bar) when several fingers overlap.

### Controller
- LB + RB shortcuts work on every controller path (also the built-in USB driver) and in every mode; the controller mode switch is always in the quick menu.
- Holding the stick or D-pad keeps scrolling, faster the longer you hold.
- D-pad left / right on the host page fixed (controllers that send the D-pad twice).
- Bolder button letters, smaller Menu / View symbols.

### Host page and app list
- "Quit all sessions" (Menu) in the bottom bar, quitting never asks and shows no notice.
- Apps show "Open" instead of "Play again".
- Tailscale: the host card shows whether Tailscale is connected and over which IP; at home the PC is always reached over the LAN first.
- After a stream ends, focus is back on the host page (not on the app on the other screen).
- App list: the selection ring glides between rows, section titles are skipped, LB / RB selects the first app of the tab, A = Open, X = favourite.
- Apps use the normal background; games blend their cover in softly with a slow zoom, and the waves in front are clearer.

### Stream
- Stream start: a real progress bar (1–100 %) along the connection steps, calmer cover and glow, a relaxed zoom into the stream.
- Quit / disconnect from the quick menu without confirmation; no more purple anywhere, your accent colour instead.
- Local mouse cursor on for Desktop / remote-monitor apps, off for games.
- The keyboard button bottom-left appears whenever the keyboard isn't visible and brings it back next to the stream.
- Performance overlay: glides in from the top and out upwards; Total · net · dec on one line, render and dropped frames on the next.

### Settings
- Opens on the Duo tab the first time, afterwards on the tab and row you used last; calmer tab switch that lands on the first setting.
