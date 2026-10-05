-- Tooltips.lua
-- What the game's tooltip says about each bag and worn item (stats, "Use: ...",
-- "Unique", durability and so on), for the item details in the app:
--   TT1|itemID<tab>line<tab>line...<newline>itemID<tab>...
-- The name line is left out (the app has the name), and so is the sell price.
-- Each item is sent once (the app keeps them) and only when nothing else is
-- waiting, a short message at a time, so the square stays calm. Out of combat only.

local _, ns = ...

local MAX_LINES = 12
local MAX_LINE = 60
local ROOM_PARTS = 2      -- at most this many parts per message
local GAP = 3             -- seconds between two tooltip messages
local sent = {}
local lastAt = -100

local function secret(v)
    return issecretvalue and issecretvalue(v)
end

local function clean(s)
    if s == nil or secret(s) then return "" end
    s = tostring(s):gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", ""):gsub("|T.-|t", ""):gsub("|A.-|a", "")
    s = s:gsub("|n", " "):gsub("[\t\r\n]", " "):gsub("^%s+", ""):gsub("%s+$", "")
    if #s > MAX_LINE then s = s:sub(1, MAX_LINE - 1) .. "…" end
    return s
end

-- The tooltip's lines (without the first, the name), or nil until the game knows the item.
local function lines(id)
    if not (C_TooltipInfo and C_TooltipInfo.GetItemByID) then return nil end
    local ok, data = pcall(C_TooltipInfo.GetItemByID, id)
    if not ok or type(data) ~= "table" or type(data.lines) ~= "table" or #data.lines == 0 then return nil end
    local out = {}
    for i = 2, #data.lines do
        local l = data.lines[i]
        local left, right = clean(l.leftText), clean(l.rightText)
        local text = right ~= "" and (left .. "  " .. right) or left
        -- The sell price is shown by the app already; empty lines carry nothing.
        if text ~= "" and not (l.price or (SELL_PRICE and left:find(SELL_PRICE, 1, true))) then
            out[#out + 1] = text
            if #out >= MAX_LINES then break end
        end
    end
    return out
end

-- The next tooltips the app has not been sent, or nil.
function ns.TooltipsPayload()
    if InCombatLockdown() or GetTime() - lastAt < GAP then return nil end
    local room = ns.StripPartBytes() * ROOM_PARTS - 4
    local out, used = {}, 0
    local all = {}
    for _, id in ipairs(ns.BagIDs()) do all[#all + 1] = id end
    for _, id in ipairs(ns.EquippedIDs()) do all[#all + 1] = id end
    for _, id in ipairs(all) do
        if not sent[id] then
            local l = lines(id)
            if l then
                local entry = id .. (#l > 0 and ("\t" .. table.concat(l, "\t")) or "")
                -- A long tooltip alone may use all parts; it is cut at a line.
                if #out == 0 and #entry > room then entry = entry:sub(1, room):match("^(.*)\t") or tostring(id) end
                if used + #entry + 1 > room then break end
                out[#out + 1] = entry
                used = used + #entry + 1
                sent[id] = true
            elseif C_Item.RequestLoadItemDataByID then
                C_Item.RequestLoadItemDataByID(id)
            end
        end
    end
    if #out == 0 then return nil end
    lastAt = GetTime()
    return "TT1|" .. table.concat(out, "\n")
end

-- Everything again (the app's Load from the game).
function ns.TooltipsAgain()
    sent = {}
end
