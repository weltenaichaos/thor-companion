# Thor Companion

A second-screen companion for WoW Forever on the AYN Thor. The game runs on the top
screen; your bags, character, map and chat appear on the bottom screen instead of
covering the game.

It stays inside Blizzard's rules. The game only talks through a normal addon, and
the app only reads what the addon shows. Nothing is automated, and every action
later on will be one key press per tap.

## How it works

1. The **ThorCompanion addon** (`addon/ThorCompanion`) draws a small square of dark
   grey cells in the top-right corner of the game screen. It carries the data: name,
   level, gold, map position, bag slots and bag contents, and the names of the items
   in your bags. It only changes when the data does; a longer message (the bags) is
   split into parts that are shown in turn.
2. The **app** (`app/`) runs on the bottom screen. It captures the top screen a
   few times a second, reads the square and draws the panels.
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
- `/thor hide` and `/thor show` turn the square off and on.
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

`tools/decode_strip.py screenshot.png [more.png ...]` decodes screenshots on a PC
(needs Pillow) and puts split messages back together.
