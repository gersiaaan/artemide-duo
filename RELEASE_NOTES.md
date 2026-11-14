## Artemide Duo v0.40

### Stream
- Rounded corners in Apple style: continuous (squircle) corners instead of quarter circles, so the edge flows into the curve like a macOS window.
- Back to the stream instead of the overview: if Artemide was left while streaming (another app on top, Claude swiped up over it), returning to Artemide opens the stream again right away. Swiping Artemide away in the recent apps ends it for good.

### Lock screen
- PIN pad on the lock screen: while the Mac is locked (MacShine tells Artemide), a small number pad floats to the right of the login, as wide as the password field. Digits, ⌫ and ↵ go straight to the Mac; the pad disappears once it is unlocked. Needs the latest MacShine.

## Artemide Duo v0.39

### Stream
- When a stream ends (you quit, or the host ends or drops it), Artemide goes straight back to the overview, without the "Connection terminated / error code" dialog. A dialog only shows when a stream never worked (blocked ports, no video).
- Rounded corners: the radius now follows the stream view's width, so they also show with native resolution (where they stayed invisible before).

## Artemide Duo v0.38

### Microphone
- Stream sound no longer gets thin or clipped while the microphone is on: the Duo records without switching into call mode. Noise filtering is done on the Mac (Apple voice isolation).

## Artemide Duo v0.37

### Stream
- Rounded stream corners now really show: drawn by their own layer above the video (v0.35 painted them where the video covered them).

## Artemide Duo v0.36

### Microphone
- Automatic gain is off: no more pumped-up noise in pauses and less clipping when you speak loudly. MacShine now filters the noise with Apple's voice isolation and sets the level.

## Artemide Duo v0.35

### Stream
- Rounded stream corners like a macOS window, edge to edge: the corners scale with the stream, so they match the window corners in the picture.

### Keyboard
- Long-press popup (ä, ö, ü …) now shows in front of the suggestion bar instead of behind it.
- Split (Keyboard 4): two-finger tap = right click, three fingers only drag; hint text updated.

## Artemide Duo v0.34

### Microphone
- **New: microphone to the Mac.** Mic button in the top bar: green = the Mac hears the Duo. Needs MacShine on the Mac; there the Duo shows up as the input device "MacShine" (Claude, Siri, Zoom … just work). Echo and noise suppression are on; headphones sound best.
- The first tap asks for the microphone permission, tap again after allowing it.

## Artemide Duo v0.33

### Keyboard
- Tap on free space of the keyboard (no key nearby) = left click, like on a trackpad (not while you're typing, so a missed key never clicks). Two fingers: click / scroll / zoom, three fingers: right click / drag.
- **Plus:** missing umlauts and accents are always corrected (uberragend → überragend), even if such a word was learned by mistake before.
- **Plus:** new words are learned only after you typed them three times, and never if they're just a word without its umlaut. A single typo no longer becomes a "known" word.
- Settings > General > Other shows which keyboard engine is running (LatinIME Plus active / loading, or the built-in word list).

## Artemide Duo v0.32

### Keyboard
- **New: Keyboard 4 – Split.** Two halves for typing with both thumbs, arranged like the iPhone Duo split keyboard: left half flush left, right half flush right, G and V on both halves, keys as big as on Slim. The whole area still works as a trackpad.
- Keyboards are numbered in a new order: Keyboard 1 Normal, Keyboard 2 Wide, Keyboard 3 Slim, Keyboard 4 Split (tap the Keyboard tab again to cycle).

### Settings
- Calmer selection: the selected row gets a soft accent tint with rounded corners instead of the blue frame.

### Surface Duo landscape
- App list: shorter labels in the bottom bar ("Favourite", "Quit all") so it never overflows.
- Host page: the last-played cover shrinks on short screens, so Resume never runs into the bottom bar.

### Connection
- A short Wi-Fi drop or access-point switch no longer marks the PC as OFFLINE; it's re-checked right away.

## Artemide Duo v0.31

### Host page
- "Quit all sessions" sits at the right end of the bottom bar (like on the app list) and only shows while a session is running.

## Artemide Duo v0.30

### Stream
- The "virtual display driver not ready" question is gone: the app simply proceeds.
- Hosts no longer flash OFFLINE on short Wi-Fi hiccups or access-point switches.

### Settings
- Every row uses the same selection frame as the app list (accent tint, thin ring), no grey highlight.
- The tab strip fades out softly at the left and right edge.
- General > Other: Artemide Duo version, developed by Chang Cao, 2026.

### Host page and app list
- No version number on the host page any more (it lives in Settings now).

## Artemide Duo v0.29

### Stream
- Performance overlay: all text is centred in the box.

## Artemide Duo v0.28

### Keyboard
- Space bar is hit more reliably: a touch that starts on a key no longer turns into a trackpad move after a small drift, and a touch just above or below the space bar still types a space.

### Host page and app list
- No grey box behind the selected app; the selection ring is the only highlight.
- Favourite now sits left of Options in the bottom bar.
- App names are trimmed, so "Remote Monitor" and "Remote Input" line up with the others.
- Resume and Terminate are treated as normal apps (normal background, no game cover).
- "Quit all sessions" shows on the host page and the app list whenever the PC is online.

### Settings
- Selected row uses the same frame as the app list (accent tint, thin ring), no grey fill.

### Stream
- Stream start progress runs a third slower and starts at 0 % (no jump to 33 %).
- Performance overlay: the box grows with all rows, so the last line is no longer cut off.
- Waves over game covers are much more transparent.

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
