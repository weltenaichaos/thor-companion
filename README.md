# Forever Companion

(Repository and addon folder keep the old name ThorCompanion, so the addon keeps its settings.)

A second-screen companion for WoW Forever on the AYN Thor. The game runs on the top
screen; your bags, character, map and chat appear on the bottom screen instead of
covering the game.

It stays inside Blizzard's rules. The game only talks through a normal addon, and
the app only reads what the addon shows. Nothing is automated, and every action
later on will be one key press per tap.

## How it works

1. The **ThorCompanion addon** (`addon/ThorCompanion`) draws a small square of dark
   grey cells in the top-right corner of the game screen. It carries the data: name,
   level, gold, map position, bag slots and bag contents, the names of the items
   in your bags, new chat lines, and the places on the zone map (quests, flight masters,
   dungeons, rares, your map pin, group members), and your character sheet (class,
   experience, item level, stats, the gear you wear with its durability). It only changes when the data does; a longer message (the bags) is
   split into parts that are shown in turn.
2. The **app** (`app/`) runs on the bottom screen. It captures the top screen a
   few times a second, reads the square and draws the panels.
   The Map tab draws the zone from those places, with an arrow for you and a line where
   you walked; pinch the map or use + and − to zoom in around you (like the minimap),
   and "Quest areas" turns the faint quest circles off. Its background is a picture of the zone the app
   takes itself: "Get zone picture" presses one key (ALT-SHIFT-F11), the addon shows the
   zone's map art (no quest icons, arrows or other addons' marks) in the middle of the
   screen for 3 seconds and says where, and the app cuts it out of one screenshot. Until then the background is a grid.
   Item icons on the Bags and Character tabs come the same way: "Get icons" presses
   CTRL-SHIFT-F12, the addon shows up to 60 icons of your bag and worn items in a grid for
   5 seconds, and the app keeps each one. Press it again for the next 60.
   Both also happen without a tap: the first time you are in a zone the addon shows its
   picture once by itself, and icons of items it has not shown before (new loot) appear
   under the data square for a moment, out of combat. `/thor auto off` stops that.
   The map is sent again every minute, so a restarted app is soon up to date.
   The Quests tab lists your quest log, and a level checker (also on the Character tab)
   shows the experience to the next level, rested experience, how many kills that is
   at the last kill's experience, and what the quests ready to turn in give.
   Tapping a quest there also starts the quest arrow at the top of the app: which way
   to go (turning with you, like the minimap) and how many yards to its nearest area,
   or to where you turn it in once it is ready.
   Long-press an item for its type, levels, sell price and what its tooltip says (stats,
   "Use: ..."; the addon sends tooltips only when nothing else is waiting). The Bags tab
   sorts by bag order, type, quality or value, and shows what your grey items sell for.
   Long-press the status line for the battery check.
   Small changes to long messages (a looted item, a quest objective) go out as a short
   TD1 change instead of the whole message, so the square changes less and bags and
   quests update sooner; changed bags go before everything else. The map arrow glides
   between positions instead of jumping.
   **The app installs the addon:** the APK carries the addon, and at start the app puts
   that version into Thor Forever's Download/Thor-Forever/AddOns folder and the game's
   Interface\AddOns (where they exist and hold another version). Type /reload in the game.
3. The **decoder** (`decoder/`) is plain Kotlin that the app uses. It is tested
   against a square drawn by the addon, upscaled and colour-shifted like on the Thor.

The app reads the game's picture directly, so four shades close to black are enough.
The Thor shifts colours and scales the game up, so every square carries its own
calibration shades and a checksum. `addon/ThorCompanion/Strip.lua` describes the layout.

## Install

**Addon:** copy `addon/ThorCompanion` into the game's `Interface\AddOns` folder. With
Thor Forever Reforged, put it in `Download/Thor-Forever/AddOns` and it is copied in
when the game starts. In game:

- `/thor` shows the data being sent (state, bags and the next page of item names).
- `/thor info` shows where the square is drawn and its settings.
- `/thor pos RIGHT TOP` moves the square: its distance in game pixels from the right
  and top edges (default 0 22, just under the zone name and clock).
- `/thor cell N` sets the cell size in game pixels, 2 to 8 (default 3; the square is
  20 cells wide).
- `/thor shade N` sets how far apart the four shades are, 4 to 80 (default 24; the
  lightest is 3 × N out of 255). Lower is darker and harder for the app to read.
- `/thor shape line` draws the same cells as one thin line along the top edge instead;
  `/thor shape square` goes back.
- `/thor map` prints what the zone picture did last and the places sent for the Map tab;
  `/thor map picture` shows the zone picture like the app's button.
- `/thor auto off` and `/thor auto on` stop and restart the zone pictures and new item
  icons that come up by themselves (default on).
- `/thor icons` shows the next page of item icons like the app's "Get icons" button.
- `/thor hide` and `/thor show` turn the square off and on.
- Public channels (General, Trade, ...) are in the app's Chat tab (General) with a write
  button each; `/thor chat channels off` leaves their lines out, `on` brings them back. Say, yell, emotes, whispers, party, raid, instance,
  guild and system lines are always sent. During boss fights the game hides chat from
  addons, so those lines show as hidden.
  The Chat tab has its own tabs (All, Group, Whispers, Guild, General). A button under
  each, or a tap on a line (to whisper its sender), opens a text field with the keyboard
  on the bottom screen. Send presses CTRL-SHIFT-F8, which the addon binds to the game's
  own Open Chat command (like Enter), waits until the addon says the box is open (TE1),
  types "/r ", "/s ", "/g ", "/1 " (a channel), your group's command or "/w Name " with
  the message (a message you start with "/" yourself goes in as written) and
  presses Enter. The addon never opens the chat box itself: the game blocks addons that
  do (the "blocked from an action only available to the Blizzard UI" popup).
- ALT-SHIFT-F12 makes the addon send everything again; the app's "Load from the game"
  button presses it (shown while the bags or character haven't arrived, for example right
  after installing the app). The app also keeps the last bags, character and map across
  restarts, but bag taps wait until fresh bags have arrived.
- `/thor taps off` and `/thor taps on` turn tap-to-use off and on (default on). With it on,
  every bag slot has its own key (CTRL-F1 and up), bound out of combat, and tapping an
  item in the app's Bags tab presses that one key, which uses the item. It also turns the
  chat key off and on.
**App:** each build on GitHub Actions uploads a debug APK
(`thor-companion-debug-apk`). Install it and open it on the bottom screen.

The app captures the screen through the Thor's built-in root service, so
Settings › Thor › Force SELinux must stay off (the default). If it reads the wrong
screen, long-press the status line to switch to the next screen.

## Build

```
./gradlew :decoder:test          # anywhere with Java 17+
./gradlew :app:assembleDebug     # needs the Android SDK
```

`tools/decode_strip.py screenshot.png [more.png ...]` decodes screenshots on a PC
(needs Pillow) and puts split messages back together.
