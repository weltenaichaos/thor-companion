-- Data.lua
-- Collects non-secret, out-of-combat-safe state and hands it to the strip.
-- The strip shows one of two kinds of frame, checked every half second:
--   TC1|name|level|copper|mapID|x|y|free/total|itemID:count:key,itemID:count:key,...
--   (key: the slot's tap key, an index into ns.ActionKeys; left out when unbound)
--   TN1|itemID,quality,name<newline>itemID,quality,name...   (names of bag items, a page at a time)
-- Name pages alternate with the state only until every name has been sent; the app
-- keeps the names it has seen. The strip is only redrawn when its content changes.

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

local function payload()
    local name = plain(UnitName("player")) or "?"
    local level = plain(UnitLevel("player")) or 0
    local mapID = C_Map.GetBestMapForUnit("player")
    local x, y = 0, 0
    if mapID then
        local pos = C_Map.GetPlayerMapPosition(mapID, "player")
        if pos then x, y = pos:GetXY() end
    end
    local head = string.format("TC1|%s|%s|%d|%d|%.4f|%.4f|", tostring(name), tostring(level),
        GetMoney() or 0, mapID or 0, x or 0, y or 0)
    local free, total, list = bagSummary(ns.StripCapacity() - #head - 16)
    return head .. free .. "/" .. total .. "|" .. list
end

local function itemName(id)
    local name = C_Item.GetItemNameByID(id)
    if not name then C_Item.RequestLoadItemDataByID(id) end  -- arrives a moment later
    return name
end

-- Each name is sent twice (so the app surely sees it), then left out until the
-- minute's refresh, so the strip stays still unless something changes.
local SENDS = 2
local REFRESH_TICKS = 120  -- one minute of half-second ticks
local sentCount = {}

-- The next page of names that still need sending, or nil when there are none.
-- With peek the page is only built, not counted as sent (for /thor).
local function namesPayload(peek)
    local room = ns.StripCapacity() - 4
    local parts, ids, used = {}, {}, 0
    for _, id in ipairs(bagIDs) do
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

local ticker, showNames
local ticks = 0
local f = CreateFrame("Frame")
f:RegisterEvent("PLAYER_LOGIN")
f:SetScript("OnEvent", function()
    ThorCompanionDB = ThorCompanionDB or {}
    ns.StripOffset = ThorCompanionDB.offset or 32
    ns.StripShow(ThorCompanionDB.hidden ~= true)
    ticker = C_Timer.NewTicker(0.5, function()
        ticks = ticks + 1
        if ticks % REFRESH_TICKS == 0 then sentCount = {} end
        local test = ns.KeyTestPayload and ns.KeyTestPayload()
        if test then ns.StripWrite(test) return end
        local ok, p = pcall(payload)
        if not ok then p = "TC1|error|" .. tostring(p) end
        -- Alternate with the state only while names are waiting to be sent.
        showNames = not showNames
        if showNames then
            local okNames, names = pcall(namesPayload)
            if okNames and names then ns.StripWrite(names) return end
        end
        ns.StripWrite(p)
    end)
end)

SLASH_THORCOMPANION1 = "/thor"
SlashCmdList.THORCOMPANION = function(msg)
    msg = (msg or ""):lower()
    local offset = tonumber(msg:match("^offset%s+(%d+)$"))
    if offset then
        ThorCompanionDB.offset = offset
        ns.StripMove(offset)
        print("|cff66ccffThor Companion|r strip moved to " .. offset .. " pixels above the bottom edge")
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
        local ok, p = pcall(payload)
        print("|cff66ccffThor Companion|r strip capacity " .. ns.StripCapacity() .. " bytes")
        print(ok and p or ("error: " .. tostring(p)))
        local okNames, names = pcall(namesPayload, true)
        print(okNames and (names or "all item names sent") or ("error: " .. tostring(names)))
    end
end
