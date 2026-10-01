# Thor Companion

A second-screen companion for WoW Forever on the AYN Thor. The game runs on the top
screen; your bags, character, map and chat appear on the bottom screen instead of
covering the game.

It stays inside Blizzard's rules. The game only talks through a normal addon, and
the app only reads what the addon shows. Nothing is automated, and every action
later on will be one key press per tap.

## How it works

1. The **ThorCompanion addon** (`addon/ThorCompanion`) draws a thin strip of coloured
   cells near the bottom of the game screen. It only changes when the data does. The strip carries
   the data: name, level, gold, map position, bag slots and bag contents, and in
   alternate frames the names of the items in your bags.
2. The **app** (`app/`) runs on the bottom screen. It captures the top screen a
   few times a second, reads the strip and draws the panels.
3. The **decoder** (`decoder/`) is plain Kotlin that the app uses. It is tested
   against a real Thor screenshot.

The Thor shifts colours and scales the game up, so the strip carries its own
64-colour calibration and a checksum in every frame. `addon/ThorCompanion/Strip.lua`
describes the layout.

## Install

**Addon:** copy `addon/ThorCompanion` into the game's `Interface\AddOns` folder. With
Thor Forever Reforged, put it in `Download/Thor-Forever/AddOns` and it is copied in
when the game starts. In game:

- `/thor` shows the data being sent (state and the next page of item names).
- `/thor info` shows where the strip is drawn.
- `/thor offset N` moves the strip N pixels up from the bottom edge (default 32).
- `/thor hide` and `/thor show` turn the strip off and on.
- `/thor taps off` and `/thor taps on` turn tap-to-use off and on (default on). With it on,
  every bag slot has its own key (CTRL-F1 and up), bound out of combat, and tapping an
  item in the app's Bags tab presses that one key, which uses the item.
- `/thor keytest` binds the CTRL and ALT function keys to a button that only prints their name, to check that
  the app's Keys tab reaches the game (keys that arrive turn green in the app). Run it again to give the keys back.

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

`tools/decode_strip.py screenshot.png` decodes a screenshot on a PC (needs Pillow).
