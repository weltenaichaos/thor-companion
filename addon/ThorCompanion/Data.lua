-- Data.lua
-- Collects non-secret, out-of-combat-safe state and hands it to the strip.
-- Test payload (pipe-separated text), refreshed once a second:
--   TC1|name|level|copper|mapID|x|y|free/total|itemID:count,itemID:count,...

local _, ns = ...

local function plain(v)
    if issecretvalue and issecretvalue(v) then return "?" end
    return v
end

local function bagSummary(maxBytes)
    local free, total, items = 0, 0, {}
    for bag = 0, 4 do
        local n = C_Container.GetContainerNumSlots(bag) or 0
        total = total + n
        free = free + (C_Container.GetContainerNumFreeSlots(bag) or 0)
        for slot = 1, n do
            local info = C_Container.GetContainerItemInfo(bag, slot)
            if info and info.itemID then
                items[#items + 1] = info.itemID .. ":" .. (info.stackCount or 1)
            end
        end
    end
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

local ticker
local f = CreateFrame("Frame")
f:RegisterEvent("PLAYER_LOGIN")
f:SetScript("OnEvent", function()
    ThorCompanionDB = ThorCompanionDB or {}
    ns.StripOffset = ThorCompanionDB.offset or 32
    ns.StripShow(ThorCompanionDB.hidden ~= true)
    ticker = C_Timer.NewTicker(1, function()
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
    end
end
