-- Data.lua
-- Collects non-secret, out-of-combat-safe state and hands it to the strip.
-- Several kinds of message, checked every half second, each sent only when it changed:
--   TS1|name|level|copper|mapID|x|y|facing|session|bagHash|version  (small, changes while walking)
--   (session: changes with every login or /reload; bagHash: a checksum of the last TB1, so
--   an app that kept the bags from before knows at once whether they are still right;
--   version: this addon's, so the app can tell when the game still runs an older one)
--   TB1|free/total|itemID:count:key,itemID:count:key,...    (the bags)
--   TC2|now|itemID:readyAt,...                                (bag items on cooldown, in GetTime() seconds)
--   TE1|n|open (or closed)                                    (whether the chat box opened, see Chat.lua)
--   TU1|n|itemID|result                                       (what came of tapping a bag item, see Actions.lua)
--   (key: the slot's tap key, an index into ns.ActionKeys; left out when unbound)
--   TN2|itemID<tab>quality<tab>itemLevel<tab>requiredLevel<tab>sellPrice<tab>type<tab>name<newline>...
--                                                             (names and details of bag and worn items, a page at a time)
--   TH1|...                                                   (new chat lines, see Chat.lua)
--   TM1|...                                                   (places on the zone map, see Map.lua)
--   TP1|... and TQ1|...                                       (character and gear, see Character.lua)
--   TL1|...                                                   (quest log and last kill, see Quests.lua)
--   TV1|...                                                   (spells to learn soon, see Spells.lua)
--   TT1|...                                                   (item tooltips, when nothing else is waiting, see Tooltips.lua)
--   TD1|...                                                   (a small change to TB1, TM1, TP1, TQ1 or TL1, see below)
-- and, for a few seconds after their keys, TW1 (zone picture, Map.lua) and TI1 (item icons, Icons.lua).
-- The app keeps the names it has seen. State and bags are sent again every five
-- minutes (names too), for an app that started after the game.

local _, ns = ...

local function plain(v)
    if issecretvalue and issecretvalue(v) then return "?" end
    return v
end

local bagIDs, bagQuality = {}, {}  -- distinct item ids in the bags, for the name pages

local function bagSummary(maxBytes)
    local free, total, items = 0, 0, {}
    local ids, seen, quality = {}, {}, {}
    for bag = 0, 4 do
        local n = C_Container.GetContainerNumSlots(bag) or 0
        total = total + n
        free = free + (C_Container.GetContainerNumFreeSlots(bag) or 0)
        for slot = 1, n do
            local info = C_Container.GetContainerItemInfo(bag, slot)
            if info and info.itemID then
                local key = ns.SlotKey and ns.SlotKey(bag, slot)
                items[#items + 1] = info.itemID .. ":" .. (info.stackCount or 1) .. (key and (":" .. key) or "")
                if not seen[info.itemID] then
                    seen[info.itemID] = true
                    ids[#ids + 1] = info.itemID
                    quality[info.itemID] = info.quality
                end
            end
        end
    end
    bagIDs, bagQuality = ids, quality
    local list = table.concat(items, ",")
    -- Too long for the strip: cut at an entry boundary, so no entry (and no tap key) is cut short.
    if #list > maxBytes then list = list:sub(1, maxBytes):match("^(.*),") or "" end
    return free, total, list
end

-- The distinct items in the bags, for the item icons (Icons.lua).
function ns.BagIDs()
    return bagIDs
end

local session = tostring(time())
local bagHash = 0

-- The same checksum the app works out (GameState.hash): over the bytes, h = (h * 31 + b) % 65536.
local function checksum(s)
    local h = 0
    for i = 1, #s do h = (h * 31 + s:byte(i)) % 65536 end
    return h
end

local getMetadata = (C_AddOns and C_AddOns.GetAddOnMetadata) or GetAddOnMetadata
local okVersion, addonVersion = pcall(getMetadata, "ThorCompanion", "Version")
addonVersion = okVersion and addonVersion or ""

local function status()
    local name = plain(UnitName("player")) or "?"
    local level = plain(UnitLevel("player")) or 0
    local mapID = C_Map.GetBestMapForUnit("player")
    local x, y = 0, 0
    if mapID then
        local pos = C_Map.GetPlayerMapPosition(mapID, "player")
        if pos then x, y = pos:GetXY() end
    end
    -- Facing in radians (0 = north, counter-clockwise), in steps of about 6 degrees.
    local facing = GetPlayerFacing and plain(GetPlayerFacing())
    facing = type(facing) == "number" and string.format("%.1f", facing) or ""
    return string.format("TS1|%s|%s|%d|%d|%.4f|%.4f|%s|%s|%d|%s", tostring(name), tostring(level),
        GetMoney() or 0, mapID or 0, x or 0, y or 0, facing, session, bagHash, addonVersion)
end

local function bags()
    local free, total, list = bagSummary(ns.StripCapacity() - 16)
    local p = "TB1|" .. free .. "/" .. total .. "|" .. list
    bagHash = checksum(p)
    return p
end

-- Name and details of an item, or nil until the game has loaded it (it arrives a moment later).
local function itemEntry(id)
    local name = C_Item.GetItemNameByID(id)
    if not name then C_Item.RequestLoadItemDataByID(id) return nil end
    local info = C_Item.GetItemInfo or GetItemInfo
    local ok, _, _, quality, _, required, itemType, subType, _, _, _, price = pcall(info, id)
    if not ok then quality, required, itemType, subType, price = nil, nil, nil, nil, nil end
    local okLevel, level = pcall(C_Item.GetDetailedItemLevelInfo, id)
    if not okLevel then level = nil end
    local kind = itemType or ""
    if subType and subType ~= "" and subType ~= itemType then kind = kind .. " / " .. subType end
    quality = quality or bagQuality[id] or C_Item.GetItemQualityByID(id) or 1
    local function clean(s) return (tostring(s or ""):gsub("[\t\n]", " ")) end
    return table.concat({ id, quality, level or 0, required or 0, price or 0, clean(kind), clean(name) }, "\t")
end

-- Each name is sent twice (so the app surely sees it), then left out until the
-- next refresh, so the square stays still unless something changes.
local SENDS = 2
local REFRESH_TICKS = 600       -- five minutes: state and bags again, for an app started late
local NAMES_REFRESH_TICKS = 600  -- five minutes: names again, for an app that was reinstalled
local MAP_REFRESH_TICKS = 120   -- one minute: the map again, so a restarted app is soon up to date
local MOVE_SECONDS = 0.5         -- while walking, the position is sent at most this often
local sentCount = {}

-- The next page of names that still need sending, or nil when there are none.
-- With peek the page is only built, not counted as sent (for /thor).
local function namesPayload(peek)
    local room = ns.StripCapacity() - 4
    local parts, ids, used = {}, {}, 0
    local all = {}
    for _, id in ipairs(bagIDs) do all[#all + 1] = id end
    for _, id in ipairs(ns.EquippedIDs()) do all[#all + 1] = id end
    for _, id in ipairs(all) do
        if (sentCount[id] or 0) < SENDS then
            local entry = itemEntry(id)
            if entry then
                if used + #entry + 1 > room then break end
                parts[#parts + 1] = entry
                ids[#ids + 1] = id
                used = used + #entry + 1
            end
        end
    end
    if #parts == 0 then return nil end
    if not peek then
        for _, id in ipairs(ids) do sentCount[id] = (sentCount[id] or 0) + 1 end
    end
    return "TN2|" .. table.concat(parts, "\n")
end

local chatAgain = false
local function chat()
    local p = ns.ChatPayload(chatAgain)
    chatAgain = false
    return p
end

-- The kinds take turns; one that has not changed since it was last sent is skipped.
-- The map places change as group members and rares move; a new zone goes out
-- at once, otherwise at most every MAP_SECONDS.
local MAP_SECONDS = 10
local mapAt, mapID = -100, nil
local mapUrgent, mapForce = false, false
local function map()
    -- Not even built while it could not be sent anyway.
    local id = C_Map.GetBestMapForUnit("player")
    local force = mapForce
    mapForce = false
    if id and tostring(id) == mapID and GetTime() - mapAt < MAP_SECONDS and not force then return nil end
    return ns.MapPayload()
end

-- Bag items on cooldown, with when they are ready (in GetTime() seconds, which the
-- app turns into minutes left). Only sent again when that list changes; in combat
-- the game may keep cooldowns secret, then the last list stays.
local cooldownKey, cooldownPayload = nil, nil
local function cooldowns()
    local out, seen = {}, {}
    local now = GetTime()
    for bag = 0, 4 do
        for slot = 1, C_Container.GetContainerNumSlots(bag) or 0 do
            local id = C_Container.GetContainerItemID(bag, slot)
            if id and not seen[id] then
                local start, duration = C_Container.GetContainerItemCooldown(bag, slot)
                if issecretvalue and (issecretvalue(start) or issecretvalue(duration)) then return cooldownPayload end
                if start and duration and start > 0 and duration > 1.5 and start + duration > now + 1 then
                    seen[id] = true
                    out[#out + 1] = id .. ":" .. math.floor(start + duration + 0.5)
                end
            end
        end
    end
    local key = table.concat(out, ",")
    if key ~= cooldownKey then
        cooldownKey = key
        cooldownPayload = "TC2|" .. math.floor(now + 0.5) .. "|" .. key
    end
    return cooldownPayload
end

local kinds = { status, bags, namesPayload, chat, map, ns.CharacterPayload, ns.GearPayload, ns.QuestsPayload, cooldowns, ns.SpellsPayload }
local lastSent = {}

-- Small changes to a long message (a looted item, a quest objective) go out as
--   TD1|<kind>|<checksum before>|<checksum after>|<at>|<cut>|<new bytes>
-- in one part instead of the whole message in several: the app takes its copy of
-- the last <kind> message (TB1, TM1, TP1, TQ1, TL1), cuts <cut> bytes at byte <at>
-- (0-based), puts the new bytes there and checks both checksums. Fewer, shorter
-- changes of the square, and bags and quests show up sooner. Every MAX_DELTAS
-- changes the whole message goes out again, for an app that missed one (the
-- bags' checksum in TS1 also tells the app when its bags are not right).
local DELTA_KINDS = { [2] = true, [5] = true, [6] = true, [7] = true, [8] = true }
local MAX_DELTAS = 8
local known, knownBefore, deltas = {}, {}, {}

local function delta(old, new)
    local a = 1
    local maxA = math.min(#old, #new)
    while a <= maxA and old:byte(a) == new:byte(a) do a = a + 1 end
    local b = 0
    while b < maxA - a + 1 and old:byte(#old - b) == new:byte(#new - b) do b = b + 1 end
    return string.format("TD1|%s|%d|%d|%d|%d|", old:sub(1, 3), checksum(old), checksum(new), a - 1, #old - b - (a - 1))
        .. new:sub(a, #new - b)
end

-- What goes on the square for kind t: the whole message p, or a TD1 for it.
local function encode(t, p)
    local base = known[t]
    knownBefore[t] = base
    known[t] = p
    if DELTA_KINDS[t] and base and base ~= p and (deltas[t] or 0) < MAX_DELTAS
        and #p > ns.StripPartBytes() then
        local d = delta(base, p)
        if #d <= ns.StripPartBytes() then
            deltas[t] = (deltas[t] or 0) + 1
            return d
        end
    end
    deltas[t] = 0
    return p
end

-- A message cut short by a tap's answer: the app may not have it.
local function interrupted(t)
    lastSent[t] = nil
    known[t] = knownBefore[t]
end
local turn = 0
local statusAt = 0

-- The status line without its position and facing, to tell walking from other changes.
local function withoutPosition(p)
    if not p then return nil end
    local f = { strsplit("|", p) }
    f[6], f[7], f[8] = "", "", ""
    return table.concat(f, "|")
end

-- A quest taken, done or handed in, or a new zone, changes the map's places: then
-- the map goes first, not after the other kinds' turns.
local mapEvents = CreateFrame("Frame")
for _, e in ipairs({ "QUEST_ACCEPTED", "QUEST_REMOVED", "QUEST_TURNED_IN", "ZONE_CHANGED_NEW_AREA", "ZONE_CHANGED" }) do
    mapEvents:RegisterEvent(e)
end
mapEvents:SetScript("OnEvent", function() mapUrgent = true end)
function ns.MapUrgent() mapUrgent = true end

-- Bags that changed (loot, a used item) go next, then the map after a quest or zone event.
local bagsUrgent = false
-- A new line from your group, a whisper or the guild goes before everything else.
local chatUrgent = false
function ns.ChatUrgent() chatUrgent = true end
local bagEvents = CreateFrame("Frame")
bagEvents:RegisterEvent("BAG_UPDATE_DELAYED")
bagEvents:SetScript("OnEvent", function() bagsUrgent = true end)

local showingTurn
local function nextMessage()
    local id = C_Map.GetBestMapForUnit("player")
    if chatUrgent then
        turn = 3  -- the chat (4) is next
        chatUrgent = false
    elseif bagsUrgent then
        turn = 1  -- the bags (2) are next
        bagsUrgent = false
    elseif mapUrgent or (id and tostring(id) ~= mapID) then
        turn = 4  -- the map (5) is next
        mapForce = mapUrgent
    end
    mapUrgent = false
    for _ = 1, #kinds do
        turn = turn % #kinds + 1
        local ok, p = pcall(kinds[turn])
        if not ok then p = "TS1|error|" .. tostring(p) end
        -- Every change redraws the square, so a moving position is sent only now and then.
        if turn == 1 and p and lastSent[1] and withoutPosition(p) == withoutPosition(lastSent[1])
            and GetTime() - statusAt < MOVE_SECONDS then
            p = lastSent[1]
        end
        -- The chat says itself when it has lines to send (each goes out twice, and the
        -- second time can look just like the first).
        if p and (p ~= lastSent[turn] or turn == 4) then
            if turn == 1 then statusAt = GetTime() end
            if turn == 5 then mapAt, mapID = GetTime(), p:match("^TM1|(%d+)") end
            lastSent[turn] = p
            showingTurn = turn
            return encode(turn, p)
        end
    end
    -- Nothing changed: time for the item tooltips the app hasn't got yet.
    local ok, tips = pcall(ns.TooltipsPayload)
    if ok and tips then
        showingTurn = nil
        return tips
    end
end

-- Everything again, from the start: for an app that was just started or
-- reinstalled (its Refresh button presses ALT-SHIFT-F12).
function ns.SendAllAgain()
    lastSent, chatAgain, sentCount, known = {}, true, {}, {}
    ns.TooltipsAgain()
end

local refreshOwner = CreateFrame("Frame")
local refreshButton = CreateFrame("Button", "ThorCompanionRefresh", UIParent)
refreshButton:RegisterForClicks("AnyUp", "AnyDown")
local refreshedAt = 0
refreshButton:SetScript("OnClick", function()
    if GetTime() - refreshedAt < 0.5 then return end
    refreshedAt = GetTime()
    ns.SendAllAgain()
end)
local refreshPending = false

function ns.BindRefreshKey()
    if InCombatLockdown() then refreshPending = true return end
    refreshPending = false
    ClearOverrideBindings(refreshOwner)
    if ns.TapsEnabled() then
        SetOverrideBindingClick(refreshOwner, true, ns.ActionKeys[ns.RefreshKey], refreshButton:GetName())
    end
end

refreshOwner:RegisterEvent("PLAYER_LOGIN")
refreshOwner:RegisterEvent("PLAYER_REGEN_ENABLED")
refreshOwner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" or refreshPending then ns.BindRefreshKey() end
end)

local ticker
local ticks = 0
local loading, quietUntil = true, 0
local loadEvents = CreateFrame("Frame")
loadEvents:RegisterEvent("LOADING_SCREEN_ENABLED")
loadEvents:RegisterEvent("LOADING_SCREEN_DISABLED")
loadEvents:RegisterEvent("PLAYER_ENTERING_WORLD")
loadEvents:SetScript("OnEvent", function(_, event)
    if event == "LOADING_SCREEN_ENABLED" then
        loading = true
    else
        loading = false
        quietUntil = GetTime() + 3
    end
end)
local f = CreateFrame("Frame")
f:RegisterEvent("PLAYER_LOGIN")
f:SetScript("OnEvent", function()
    ThorCompanionDB = ThorCompanionDB or {}
    ns.StripShow(ThorCompanionDB.hidden ~= true)
    ticker = C_Timer.NewTicker(0.5, function()
        -- Nothing while a loading screen is up, and for a moment after: the game is
        -- busy setting up the zone then, and asking it things is better left alone.
        if loading or GetTime() < quietUntil then return end
        ticks = ticks + 1
        if ticks % REFRESH_TICKS == 0 then lastSent, chatAgain, known = {}, true, {} end
        if ticks % NAMES_REFRESH_TICKS == 0 then sentCount = {} end
        if ticks % MAP_REFRESH_TICKS == 0 then lastSent[5], known[5] = nil, nil end
        -- What came of a tap goes out at once; whatever it cut short is sent again.
        local okOpen, opened = pcall(ns.ChatOpenPayload)
        if okOpen and opened then
            if showingTurn and ns.StripBusy() then interrupted(showingTurn) end showingTurn = nil
            ns.StripWrite(opened)
            return
        end
        local okUse, use = pcall(ns.UsePayload)
        if okUse and use then
            if showingTurn and ns.StripBusy() then interrupted(showingTurn) end showingTurn = nil
            ns.StripWrite(use)
            return
        end
        -- The zone picture is up: say where, so the app can take it.
        local okPicture, picture = pcall(ns.MapPicturePayload)
        if not okPicture then picture = nil end
        if picture then
            if showingTurn and ns.StripBusy() then interrupted(showingTurn) end showingTurn = nil
            ns.StripWrite(picture)
            return
        end
        local okIcons, icons = pcall(ns.IconsPayload)
        if okIcons and icons then
            if showingTurn and ns.StripBusy() then interrupted(showingTurn) end showingTurn = nil
            ns.StripWrite(icons)
            return
        end
        if ns.StripBusy() then return end
        -- What the app has no picture of yet comes up by itself, one at a time.
        local okAuto, shown = pcall(ns.MapPictureAuto)
        if okAuto and shown then return end
        okAuto, shown = pcall(ns.IconsAuto)
        if okAuto and shown then return end
        local p = nextMessage()
        if p then ns.StripWrite(p) end
    end)
end)

local function setting(key, value, low, high, what)
    value = tonumber(value)
    if not value or value < low or value > high then
        print("|cff66ccffForever Companion|r " .. what .. " must be " .. low .. " to " .. high)
        return
    end
    ThorCompanionDB[key] = value
    ns.StripRefresh()
end

SLASH_THORCOMPANION1 = "/thor"
SlashCmdList.THORCOMPANION = function(msg)
    msg = (msg or ""):lower()
    local right, top = msg:match("^pos%s+(%d+)%s+(%d+)$")
    local cell = msg:match("^cell%s+(%d+)$")
    local shadeStep = msg:match("^shade%s+(%d+)$")
    if right then
        setting("right", right, 0, 2000, "the distance from the right edge")
        setting("top", top, 0, 1200, "the distance from the top edge")
    elseif msg == "shape line" or msg == "shape square" then
        ThorCompanionDB.shape = msg:sub(7)
        ThorCompanionDB.top = nil  -- each shape has its own default place
        ns.StripRefresh()
    elseif cell then
        setting("cell", cell, 2, 8, "the cell size")
    elseif shadeStep then
        setting("shade", shadeStep, 4, 80, "the shade step")
    elseif msg == "tips on" or msg == "tips off" then
        ThorCompanionDB.tips = msg == "tips on"
        print("|cff66ccffForever Companion|r item tooltips for the app " .. (msg == "tips on" and "on" or "off"))
    elseif msg == "chat channels on" or msg == "chat channels off" then
        ns.ChatChannels(msg == "chat channels on")
        print("|cff66ccffForever Companion|r public channels in the app's chat " .. (msg:sub(-2) == "on" and "on" or "off"))
    elseif msg == "map picture" then
        ns.MapPictureShow()
    elseif msg == "auto on" or msg == "auto off" then
        ThorCompanionDB.auto = (msg == "auto on")
        print("|cff66ccffForever Companion|r zone pictures and new item icons by themselves " .. (msg == "auto on" and "on" or "off"))
    elseif msg == "icons" then
        ns.IconsShow()
        print("|cff66ccffForever Companion|r " .. ns.IconsInfo())
    elseif msg == "map" then
        print("|cff66ccffForever Companion|r " .. ns.MapInfo())
    elseif msg == "info" then
        print("|cff66ccffForever Companion|r " .. ns.StripInfo())
    elseif msg == "taps" then
        print("|cff66ccffForever Companion|r " .. ns.TapsInfo())
    elseif msg == "taps on" or msg == "taps off" then
        ns.SetTaps(msg == "taps on")
        print("|cff66ccffForever Companion|r tap to use " .. (msg == "taps on" and "on" or "off") ..
            (InCombatLockdown() and " (after combat)" or ""))
    elseif msg == "hide" or msg == "show" then
        ThorCompanionDB.hidden = (msg == "hide")
        ns.StripShow(msg == "show")
    else
        print("|cff66ccffForever Companion|r " .. ns.StripInfo())
        for _, kind in ipairs({ status, bags, ns.MapPayload }) do
            local ok, p = pcall(kind)
            print(ok and p or ("error: " .. tostring(p)))
        end
        local okNames, names = pcall(namesPayload, true)
        print(okNames and (names or "all item names sent") or ("error: " .. tostring(names)))
    end
end
