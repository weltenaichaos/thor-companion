-- Data.lua
-- Collects non-secret, out-of-combat-safe state and hands it to the strip.
-- Several kinds of message, checked every half second, each sent only when it changed:
--   TS1|name|level|copper|mapID|x|y|facing                  (small, changes while walking)
--   TB1|free/total|itemID:count:key,itemID:count:key,...    (the bags)
--   (key: the slot's tap key, an index into ns.ActionKeys; left out when unbound)
--   TN1|itemID,quality,name<newline>itemID,quality,name...   (names of bag items, a page at a time)
--   TH1|...                                                   (new chat lines, see Chat.lua)
--   TM1|...                                                   (places on the zone map, see Map.lua)
--   TP1|... and TQ1|...                                       (character and gear, see Character.lua)
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
    return string.format("TS1|%s|%s|%d|%d|%.4f|%.4f|%s", tostring(name), tostring(level),
        GetMoney() or 0, mapID or 0, x or 0, y or 0, facing)
end

local function bags()
    local free, total, list = bagSummary(ns.StripCapacity() - 16)
    return "TB1|" .. free .. "/" .. total .. "|" .. list
end

local function itemName(id)
    local name = C_Item.GetItemNameByID(id)
    if not name then C_Item.RequestLoadItemDataByID(id) end  -- arrives a moment later
    return name
end

-- Each name is sent twice (so the app surely sees it), then left out until the
-- next refresh, so the square stays still unless something changes.
local SENDS = 2
local REFRESH_TICKS = 600       -- five minutes: state and bags again, for an app started late
local NAMES_REFRESH_TICKS = 600  -- five minutes: names again, for an app that was reinstalled
local MOVE_SECONDS = 0.7         -- while walking, the position is sent at most this often
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
            local name = itemName(id)
            if name then
                local q = bagQuality[id] or C_Item.GetItemQualityByID(id) or 1
                local entry = id .. "," .. q .. "," .. (name:gsub("\n", " "))
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
    return "TN1|" .. table.concat(parts, "\n")
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
local function map()
    -- Not even built while it could not be sent anyway.
    local id = C_Map.GetBestMapForUnit("player")
    if id and tostring(id) == mapID and GetTime() - mapAt < MAP_SECONDS then return nil end
    return ns.MapPayload()
end

local kinds = { status, bags, namesPayload, chat, map, ns.CharacterPayload, ns.GearPayload }
local lastSent = {}
local turn = 0
local statusAt = 0

-- The status line without its position and facing, to tell walking from other changes.
local function withoutPosition(p)
    return p and (p:match("^(.*)|[^|]*|[^|]*|[^|]*$") or p)
end

local function nextMessage()
    for _ = 1, #kinds do
        turn = turn % #kinds + 1
        local ok, p = pcall(kinds[turn])
        if not ok then p = "TS1|error|" .. tostring(p) end
        -- Every change redraws the square, so a moving position is sent only now and then.
        if turn == 1 and p and lastSent[1] and withoutPosition(p) == withoutPosition(lastSent[1])
            and GetTime() - statusAt < MOVE_SECONDS then
            p = lastSent[1]
        end
        if p and p ~= lastSent[turn] then
            if turn == 1 then statusAt = GetTime() end
            if turn == 5 then mapAt, mapID = GetTime(), p:match("^TM1|(%d+)") end
            lastSent[turn] = p
            return p
        end
    end
end

local ticker
local ticks = 0
local f = CreateFrame("Frame")
f:RegisterEvent("PLAYER_LOGIN")
f:SetScript("OnEvent", function()
    ThorCompanionDB = ThorCompanionDB or {}
    ns.StripShow(ThorCompanionDB.hidden ~= true)
    ticker = C_Timer.NewTicker(0.5, function()
        ticks = ticks + 1
        if ticks % REFRESH_TICKS == 0 then lastSent, chatAgain = {}, true end
        if ticks % NAMES_REFRESH_TICKS == 0 then sentCount = {} end
        local test = ns.KeyTestPayload and ns.KeyTestPayload()
        if test then ns.StripWrite(test) lastSent = {} return end
        -- The zone picture is up: say where, so the app can take it.
        local okPicture, picture = pcall(ns.MapPicturePayload)
        if not okPicture then picture = nil end
        if picture then ns.StripWrite(picture) lastSent = {} return end
        if ns.StripBusy() then return end
        local p = nextMessage()
        if p then ns.StripWrite(p) end
    end)
end)

local function setting(key, value, low, high, what)
    value = tonumber(value)
    if not value or value < low or value > high then
        print("|cff66ccffThor Companion|r " .. what .. " must be " .. low .. " to " .. high)
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
    elseif msg == "chat channels on" or msg == "chat channels off" then
        ns.ChatChannels(msg == "chat channels on")
        print("|cff66ccffThor Companion|r public channels in the app's chat " .. (msg:sub(-2) == "on" and "on" or "off"))
    elseif msg == "map picture" then
        ns.MapPictureShow()
    elseif msg == "map" then
        print("|cff66ccffThor Companion|r " .. ns.MapInfo())
    elseif msg == "info" then
        print("|cff66ccffThor Companion|r " .. ns.StripInfo())
    elseif msg == "taps" then
        print("|cff66ccffThor Companion|r " .. ns.TapsInfo())
    elseif msg == "taps on" or msg == "taps off" then
        ns.SetTaps(msg == "taps on")
        print("|cff66ccffThor Companion|r tap to use " .. (msg == "taps on" and "on" or "off") ..
            (InCombatLockdown() and " (after combat)" or ""))
    elseif msg == "keytest" then
        ns.KeyTest()
    elseif msg == "hide" or msg == "show" then
        ThorCompanionDB.hidden = (msg == "hide")
        ns.StripShow(msg == "show")
    else
        print("|cff66ccffThor Companion|r " .. ns.StripInfo())
        for _, kind in ipairs({ status, bags, ns.MapPayload }) do
            local ok, p = pcall(kind)
            print(ok and p or ("error: " .. tostring(p)))
        end
        local okNames, names = pcall(namesPayload, true)
        print(okNames and (names or "all item names sent") or ("error: " .. tostring(names)))
    end
end
