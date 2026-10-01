-- Data.lua
-- Collects non-secret, out-of-combat-safe state and hands it to the strip.
-- Every half second the strip shows one frame, alternating between two kinds:
--   TC1|name|level|copper|mapID|x|y|free/total|itemID:count,itemID:count,...
--   TN1|itemID,quality,name<newline>itemID,quality,name...   (names of bag items, a page at a time)
-- The app keeps the names it has seen, so each name only needs to arrive once.

local _, ns = ...

local function plain(v)
    if issecretvalue and issecretvalue(v) then return "?" end
    return v
end

local bagIDs, bagQuality = {}, {}  -- distinct item ids in the bags, for the name pages
local nameNext = 1

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
                items[#items + 1] = info.itemID .. ":" .. (info.stackCount or 1)
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
    if #list > maxBytes then list = list:sub(1, maxBytes) end
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

-- The next page of names, starting where the last page stopped, or nil when no
-- name is known yet.
local function namesPayload()
    local n = #bagIDs
    if n == 0 then return nil end
    local room = ns.StripCapacity() - 4
    local parts, used = {}, 0
    local start = ((nameNext - 1) % n) + 1
    nameNext = start
    for k = 0, n - 1 do
        local i = ((start - 1 + k) % n) + 1
        local id = bagIDs[i]
        local name = itemName(id)
        if name then
            local q = bagQuality[id] or C_Item.GetItemQualityByID(id) or 1
            local entry = id .. "," .. q .. "," .. (name:gsub("\n", " "))
            if used + #entry + 1 > room then
                nameNext = i
                break
            end
            parts[#parts + 1] = entry
            used = used + #entry + 1
        end
        nameNext = i + 1
    end
    if #parts == 0 then return nil end
    return "TN1|" .. table.concat(parts, "\n")
end

local ticker, showNames
local f = CreateFrame("Frame")
f:RegisterEvent("PLAYER_LOGIN")
f:SetScript("OnEvent", function()
    ThorCompanionDB = ThorCompanionDB or {}
    ns.StripOffset = ThorCompanionDB.offset or 32
    ns.StripShow(ThorCompanionDB.hidden ~= true)
    ticker = C_Timer.NewTicker(0.5, function()
        showNames = not showNames
        if showNames then
            local ok, p = pcall(namesPayload)
            if ok and p then ns.StripWrite(p) return end
        end
        local ok, p = pcall(payload)
        ns.StripWrite(ok and p or ("TC1|error|" .. tostring(p)))
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
    elseif msg == "hide" or msg == "show" then
        ThorCompanionDB.hidden = (msg == "hide")
        ns.StripShow(msg == "show")
    else
        local ok, p = pcall(payload)
        print("|cff66ccffThor Companion|r strip capacity " .. ns.StripCapacity() .. " bytes")
        print(ok and p or ("error: " .. tostring(p)))
        local okNames, names = pcall(namesPayload)
        print(okNames and (names or "no item names loaded yet") or ("error: " .. tostring(names)))
    end
end
