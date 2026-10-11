First pre-release of Forever Companion: WoW Forever's bags, character, quests, map and chat on the Ayn Thor's bottom screen, read from a small dark square the addon draws by the minimap. A tap on the bottom screen sends one key press, never more.

## Install

1. Install `ForeverCompanion-<version>.apk` on the Thor. Your browser may warn about the file; that is a false alarm.
2. Open the app once. It copies the addon into your Thor-Forever folder, and the launcher puts it into the game at the next start. To install it by hand instead, unzip `ForeverCompanion-addon-<version>.zip` into the game's `Interface/AddOns`.
3. Start WoW Forever through the launcher. App and addon versions must match; the status line says when the game still runs an older addon.

## What's in it

- **Bags:** items with icons and names, sorted, grey value, cooldowns, item details on long-press, tap to use.
- **Character:** gear, stats, level checker and the spells to learn at your next trainer visit.
- **Quests:** the whole quest log, nearest first, with yards and turn-in towns, and an Errands card that groups what is ready by town.
- **Map:** zone picture, your position and quest areas, with pinch zoom.
- **Chat:** All, Group, Whispers, Guild and General tabs. Reply on the bottom screen's keyboard (the text is pasted into the game), with quick replies.
- **Start-up card:** shows what is still loading after a login. Data is kept per character.
- **Game following:** the app follows the game through its files (start, login, logout, quit), so it reads the screen only when there is something to read.

## Good to know

- `/thor speed safe` sends every part of the square twice (slower, for when reads are flaky). `/thor speed fast` is the default.
- The square: `/thor pos X Y` (place from the top right), `/thor cell N` (cell size), `/thor shade N` (brightness), `/thor hide` and `/thor show`.
- `/thor taps off` stops the bottom screen's taps from using items; `/thor tips off` and `/thor auto off` stop the item tooltips and the zone pictures and icons the addon shows by itself.
