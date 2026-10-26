## Artemide Duo v0.24

**Controller**
- Controller button in the keyboard panel now has three states: **G** gamepad, **D** desktop/mouse (desktop & remote only), **crossed out** = controller ignored in the stream except the LB + RB shortcuts. Also in the quick menu and on LB + RB + View.
- Gamepad cursor: slower and much more precise for small stick movements (10 = the old 5).
- Shortcuts can be rebound by pressing the controller button (A, B, X, Y, Menu, View, L3, R3), with Reset / Turn off.
- Panel on the other screen no longer steals focus: controller, mouse and shortcuts keep working while you tap the keyboard (Gboard is the only exception).

**Host page & app list**
- X = Play / Resume, Menu ≡ = Quit, View ⧉ = Options, Y = Settings, B = Back/Exit. These buttons and the host tabs / bottom bars can't be selected with the stick any more.
- App list steps row by row (Last played → Favourites → All), opens on the last played app; Resume connects directly.
- Back on the host page lands on Open.

**Stream**
- Apple-style disconnect: the stream shrinks into the centre and the host page settles in.
- Keyboard panel appears from the stream screen with a soft spring and slides back up when hidden.
- Performance overlay: glass look, Inter font, 40 % opacity by default.

**Settings**
- Regrouped into clear sections per tab, codec under Video, nothing hidden behind "advanced" any more, GitHub / update links removed.
- Duo tab: "New in Artemide Duo" with the new settings (overlay, gamepad cursor speed, shortcuts) plus a keyboard vibration slider (default 50 %).
