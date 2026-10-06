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
   The whole quest log is shown: a long one comes in several pages (TL1, then TL2).
   A chip on the level card says which class spells you can learn now at your trainer
   and at the next levels; tapping it shows them with the trainer's prices (known
   once you have opened your class trainer).
   A folded "Errands (N)" card above the quests groups what you can hand in by town
   (the town comes from the nearest flight master to the turn-in), plus your class
   trainer when spells are ready there, so you can plan one trip for several quests.
   Stops in this zone come first, nearest on top; the stop worth the most XP is marked.
   Quests show their level in the game's colours and, in this zone, how many yards
   away they are, nearest first. Tapping one selects it: a white ring on its places,
   the map zooms to show you and the nearest one, and a line above the map keeps the
   yards up to date (✕ clears it). The zone's size in yards comes from the game, or
   from walking around a little where the game doesn't say, from the world position or
   from your running speed: run straight for about ten seconds (`/thor map` shows
   which, and what it measured; a few more runs make it more exact).
   Each quest also carries where the game's own arrow points next ("mapID:x:y:zone"),
   so a quest ready to turn in points at whoever takes it, and a quest whose next step
   is in another zone says which zone.
   Long-press an item for its type, levels, sell price and what its tooltip says (stats,
   "Use: ..."; the addon sends tooltips only when nothing else is waiting). The Bags tab
   sorts by bag order, type, quality or value, and shows what your grey items sell for.
   Long-press the status line for the battery check.
   Small changes to long messages (a looted item, a quest objective) go out as a short
   TD1 change instead of the whole message, so the square changes less and bags and
   quests update sooner; changed bags go before everything else. The map arrow glides
   between positions instead of jumping.
   **The app installs the addon:** the APK carries the addon, and at start the app puts
   that version into Thor Forever's Download/Thor-Forever/AddOns folder (when it holds
   another version); the launcher copies it into the game at the next start.
   While the square isn't on the screen (the game starting, the character screen, a
   loading screen) the app reads the game's screen only every 10 seconds (30 seconds
   right after the square went away, then every 5): screenshots taken while the game
   loaded into the world crashed it.
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
- `/thor tips off` stops the item tooltips for the app (`on` turns them back on).
  The addon sends nothing while a loading screen is up and for 3 seconds after, and
  reads no tooltips in the first 30 seconds after one.
- Public channels (General, Trade, ...) are in the app's Chat tab (General) with a write
  button each; `/thor chat channels off` leaves their lines out, `on` brings them back. Say, yell, emotes, whispers, party, raid, instance,
  guild and system lines are always sent, before channel lines and each twice (in case
  the app missed one); a busy Trade channel can't push them out. During boss fights the game hides chat from
  addons, so those lines show as hidden.
  The Chat tab has its own tabs (All, Group, Whispers, Guild, General). A button under
  each, or a tap on a line (to whisper its sender), opens a text field with the keyboard
  on the bottom screen. Send presses CTRL-SHIFT-F8, which the addon binds to the game's
  own Open Chat command (like Enter), waits until the addon says the box is open (TE1),
  puts in "/r ", "/s ", "/g ", "/1 " (a channel), your group's command or "/w Name " with
  the message (a message you start with "/" yourself goes in as written) and
  presses Enter. The line goes in as one paste (the clipboard, then CTRL-V); TE1's
  last field, the box's text length, confirms it arrived. If it doesn't, the app types
  the line key by key instead, and keeps doing so until it restarts. The addon never opens the chat box itself: the game blocks addons that
  do (the "blocked from an action only available to the Blizzard UI" popup).
- The Exit button at the end of the status line closes the app (tap it twice): it saves
  what it shows, ends its key helper and leaves the recent apps.
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
