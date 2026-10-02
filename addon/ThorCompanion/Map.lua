-- Map.lua
-- What the app's Map tab draws, besides your own position (TS1): the zone's name
-- and the places on it. Sent as
--   TM1|mapID|zone|parent zone<newline><kind>,<x>,<y>,<label><newline>...
-- with x and y from 0 to 1 on the zone map, like the position. Kinds:
--   q quest objective area   Q quest ready to turn in   a quest to pick up
--   w your map pin
--   c your corpse            f flight master            d dungeon or raid entrance
--   p other place on the map (towns, events)            v rare or treasure
--   g group member
-- The app draws these together with the path you walked, over a picture of the
-- zone it took itself: its "Get zone picture" button presses one key
-- (ALT-SHIFT-F11), and the addon shows the zone's map art (the same art as the
-- world map, without quest icons, arrows or other addons' marks) in the middle
-- of the screen for a few seconds, while
--   TW1|mapID|left|top|width|height|cell
-- says where that is, in game pixels from the top-left corner of the data
-- square, so the app can cut it out of one screenshot and keep it.

local _, ns = ...

local MAX_LABEL = 32

local function secret(v)
    return issecretvalue and issecretvalue(v)
end

local function label(s)
    if s == nil or secret(s) then return "" end
    s = tostring(s):gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", ""):gsub("[\r\n]", " ")
    if #s > MAX_LABEL then s = s:sub(1, MAX_LABEL - 1) .. "…" end
    return s
end

local function xy(pos)
    if not pos or secret(pos) then return end
    local x, y = pos.x, pos.y
    if pos.GetXY then x, y = pos:GetXY() end
    if not x or not y or secret(x) or x <= 0 or y <= 0 or x >= 1 or y >= 1 then return end
    return x, y
end

-- Each source on its own: one the game refuses (or lacks) does not stop the others.
local function each(entries, source)
    local ok, err = pcall(source, function(kind, pos, text)
        local x, y = xy(pos)
        if x then entries[#entries + 1] = string.format("%s,%.3f,%.3f,%s", kind, x, y, label(text)) end
    end)
    return ok or err
end

local function places(mapID)
    local entries = {}
    -- Most useful first: the list is cut when it is too long for the square.
    each(entries, function(add)
        local wp = C_Map.GetUserWaypoint and C_Map.GetUserWaypoint()
        if wp and wp.uiMapID == mapID then add("w", wp.position, "Map pin") end
    end)
    each(entries, function(add)
        if C_DeathInfo and UnitIsDeadOrGhost("player") then add("c", C_DeathInfo.GetCorpseMapPosition(mapID), "Your corpse") end
    end)
    each(entries, function(add)
        for _, q in ipairs(C_QuestLog.GetQuestsOnMap(mapID) or {}) do
            local done = C_QuestLog.ReadyForTurnIn and C_QuestLog.ReadyForTurnIn(q.questID)
            add(done and "Q" or "q", q, C_QuestLog.GetTitleForQuestID(q.questID))
        end
    end)
    each(entries, function(add)
        C_QuestLine.RequestQuestLinesForMap(mapID)
        for _, q in ipairs(C_QuestLine.GetAvailableQuestLines(mapID) or {}) do
            if not q.isHidden then add("a", q, q.questName) end
        end
    end)
    each(entries, function(add)
        for i = 1, GetNumGroupMembers() > 0 and 4 or 0 do
            local unit = "party" .. i
            if UnitExists(unit) then add("g", C_Map.GetPlayerMapPosition(mapID, unit), UnitName(unit)) end
        end
    end)
    each(entries, function(add)
        for _, n in ipairs(C_TaxiMap.GetTaxiNodesForMap(mapID) or {}) do add("f", n.position, n.name) end
    end)
    each(entries, function(add)
        for _, d in ipairs(C_EncounterJournal.GetDungeonEntrancesForMap(mapID) or {}) do add("d", d.position, d.name) end
    end)
    each(entries, function(add)
        for _, id in ipairs(C_AreaPoiInfo.GetAreaPOIForMap(mapID) or {}) do
            local p = C_AreaPoiInfo.GetAreaPOIInfo(mapID, id)
            if p then add("p", p.position, p.name) end
        end
    end)
    each(entries, function(add)
        for _, guid in ipairs(C_VignetteInfo.GetVignettes() or {}) do
            local v = C_VignetteInfo.GetVignetteInfo(guid)
            if v and v.onWorldMap then add("v", C_VignetteInfo.GetVignettePosition(guid, mapID), v.name) end
        end
    end)
    return entries
end

-- The map message for where you are now, or nil when the game has no map here.
function ns.MapPayload()
    local mapID = C_Map.GetBestMapForUnit("player")
    if not mapID then return nil end
    local info = C_Map.GetMapInfo(mapID)
    local parent = info and info.parentMapID and info.parentMapID > 0 and C_Map.GetMapInfo(info.parentMapID)
    local head = "TM1|" .. mapID .. "|" .. label(info and info.name):gsub("|", "/") .. "|" .. label(parent and parent.name):gsub("|", "/")
    local room = ns.StripCapacity() - #head - 1
    local out, used = {}, 0
    for _, e in ipairs(places(mapID)) do
        if used + #e + 1 > room then break end
        out[#out + 1] = e
        used = used + #e + 1
    end
    return head .. "\n" .. table.concat(out, "\n")
end

-- The zone picture, shown for PICTURE_SECONDS after the key.
local PICTURE_SECONDS = 3
local PICTURE_WIDTH = 960       -- game pixels
local pictureUntil, pictureMap = 0, nil

local function physical(frame)
    local l, b, w, h = frame:GetRect()
    if not l then return end
    local k = frame:GetEffectiveScale() * select(2, GetPhysicalScreenSize()) / 768
    return l * k, (b + h) * k, w * k, h * k
end

-- The clean copy: the map's art tiles and the explored areas on top, drawn like
-- the world map does it (Blizzard's MapCanvasDetailLayer and MapExplorationPin).
local art, artTextures = nil, {}

local function texture(i)
    local t = artTextures[i]
    if not t then
        t = art:CreateTexture(nil, "ARTWORK")
        artTextures[i] = t
    end
    t:SetTexCoord(0, 1, 0, 1)
    t:Show()
    return t
end

-- Sizes in the map art's own pixels; k turns them into the frame's units.
local function drawArt(mapID)
    if not art then
        art = CreateFrame("Frame", nil, UIParent)
        art:SetFrameStrata("FULLSCREEN_DIALOG")
        art:SetClipsChildren(true)
        -- Like the data square: 1 unit = 1 game pixel, whatever the UI scale.
        art:SetIgnoreParentScale(true)
        local bg = art:CreateTexture(nil, "BACKGROUND")
        bg:SetAllPoints()
        bg:SetColorTexture(0, 0, 0, 1)
    end
    for _, t in ipairs(artTextures) do t:Hide() end
    local layer = (C_Map.GetMapArtLayers(mapID) or {})[1]
    if not layer then return false end
    art:SetScale(768 / select(2, GetPhysicalScreenSize()))
    art:SetSize(PICTURE_WIDTH, PICTURE_WIDTH * layer.layerHeight / layer.layerWidth)
    art:ClearAllPoints()
    art:SetPoint("CENTER", UIParent, "CENTER", 0, 0)
    local k = PICTURE_WIDTH / layer.layerWidth
    local n = 0
    local cols = math.ceil(layer.layerWidth / layer.tileWidth)
    for i, file in ipairs(C_Map.GetMapArtLayerTextures(mapID, 1) or {}) do
        n = n + 1
        local t = texture(n)
        t:SetTexture(file, nil, nil, "TRILINEAR")
        t:SetSize(layer.tileWidth * k, layer.tileHeight * k)
        t:ClearAllPoints()
        t:SetPoint("TOPLEFT", art, "TOPLEFT", ((i - 1) % cols) * layer.tileWidth * k, -math.floor((i - 1) / cols) * layer.tileHeight * k)
    end
    for _, e in ipairs(C_MapExplorationInfo.GetExploredMapTextures(mapID) or {}) do
        if not e.isShownByMouseOver then
            for row = 1, e.numTexturesTall do
                local h, fileH = 256, 256
                if row == e.numTexturesTall then
                    h = e.textureHeight % 256
                    if h == 0 then h = 256 end
                    fileH = 16
                    while fileH < h do fileH = fileH * 2 end
                end
                for col = 1, e.numTexturesWide do
                    local w, fileW = 256, 256
                    if col == e.numTexturesWide then
                        w = e.textureWidth % 256
                        if w == 0 then w = 256 end
                        fileW = 16
                        while fileW < w do fileW = fileW * 2 end
                    end
                    n = n + 1
                    local t = texture(n)
                    t:SetTexture(e.fileDataIDs[(row - 1) * e.numTexturesWide + col], nil, nil, "TRILINEAR")
                    t:SetTexCoord(0, w / fileW, 0, h / fileH)
                    t:SetSize(w * k, h * k)
                    t:ClearAllPoints()
                    t:SetPoint("TOPLEFT", art, "TOPLEFT", (e.offsetX + 256 * (col - 1)) * k, -(e.offsetY + 256 * (row - 1)) * k)
                end
            end
        end
    end
    art:Show()
    return n > 0
end

local note = "no picture asked for yet"

-- The key: show the art of the zone you are in.
function ns.MapPictureShow()
    local mapID = C_Map.GetBestMapForUnit("player")
    if not mapID then note = "no map here" return end
    local ok, drawn = pcall(drawArt, mapID)
    if not ok or not drawn then
        note = "could not draw map " .. mapID .. ": " .. tostring(drawn)
        if art then art:Hide() end
        return
    end
    pictureMap, pictureUntil = mapID, GetTime() + PICTURE_SECONDS
    note = "showed map " .. mapID .. " at " .. date("%H:%M:%S")
end

-- TW1 while the art is up, else nil (and the art goes away).
function ns.MapPicturePayload()
    if GetTime() > pictureUntil then
        if art and art:IsShown() then art:Hide() end
        return nil
    end
    local sl, st = physical(ns.StripFrame())
    local cl, ct, cw, ch = physical(art)
    if not sl or not cl then return nil end
    local r = function(v) return math.floor(v + 0.5) end
    return string.format("TW1|%d|%d|%d|%d|%d|%d", pictureMap, r(cl - sl), r(st - ct), r(cw), r(ch), (ThorCompanionDB and ThorCompanionDB.cell) or 3)
end

-- For /thor map: what the map picture did last, and the places message.
function ns.MapInfo()
    local ok, p = pcall(ns.MapPayload)
    return "zone picture: " .. note .. "\n" .. (ok and (p or "no map here") or ("error: " .. tostring(p)))
end

-- The picture key, bound out of combat like the bag keys (and off with /thor taps off).
local keyOwner = CreateFrame("Frame")
local keyButton = CreateFrame("Button", "ThorCompanionMapPicture", UIParent)
keyButton:RegisterForClicks("AnyUp", "AnyDown")
local lastClick = 0
keyButton:SetScript("OnClick", function()
    if GetTime() - lastClick < 0.5 then return end
    lastClick = GetTime()
    ns.MapPictureShow()
end)
local keyPending = false

function ns.BindMapKey()
    if InCombatLockdown() then keyPending = true return end
    keyPending = false
    ClearOverrideBindings(keyOwner)
    if ns.TapsEnabled() then
        SetOverrideBindingClick(keyOwner, true, ns.ActionKeys[ns.PictureKey], keyButton:GetName())
    end
end

keyOwner:RegisterEvent("PLAYER_LOGIN")
keyOwner:RegisterEvent("PLAYER_REGEN_ENABLED")
keyOwner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" or keyPending then ns.BindMapKey() end
end)
