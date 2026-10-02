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
-- zone it took itself: when you open the world map (zoomed out, standing still),
-- the addon covers it for a few seconds with a clean copy (the same map art,
-- without quest icons, your arrow or other addons' marks), and
--   TW1|mapID|left|top|width|height|cell
-- says where that is on screen, in game pixels from the top-left corner of the
-- data square, so the app can cut it out of one screenshot and keep it.

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

-- The zone picture: each zoomed-out world map is offered once per session, for
-- PICTURE_SECONDS, after it has been still for a moment (the app takes it then).
local PICTURE_SECONDS = 3
local offered = {}
local stillSince, stillMap, offerUntil, offerMap = 0, nil, 0, nil

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
        local bg = art:CreateTexture(nil, "BACKGROUND")
        bg:SetAllPoints()
        bg:SetColorTexture(0, 0, 0, 1)
    end
    for _, t in ipairs(artTextures) do t:Hide() end
    local layer = (C_Map.GetMapArtLayers(mapID) or {})[1]
    if not layer then return false end
    art:ClearAllPoints()
    art:SetAllPoints(WorldMapFrame.ScrollContainer.Child)
    local k = art:GetWidth() / layer.layerWidth
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

function ns.MapPicturePayload()
    local wm = WorldMapFrame
    local now = GetTime()
    local ok, mapID = pcall(function()
        local scroll = wm and wm:IsVisible() and wm.ScrollContainer
        if not scroll or scroll.IsZoomedOut and not scroll:IsZoomedOut() then return end
        if wm:GetAlpha() < 0.99 or IsPlayerMoving() then return end
        return wm:GetMapID()
    end)
    if not ok or not mapID then
        stillMap = nil
        if art then art:Hide() end
        return nil
    end
    if mapID ~= stillMap then stillMap, stillSince = mapID, now end
    if offerMap ~= mapID or now > offerUntil then
        if art then art:Hide() end
        if offered[mapID] or now - stillSince < 1 then return nil end
        offered[mapID] = true
        offerMap, offerUntil = mapID, now + PICTURE_SECONDS
        local drawn, okArt = pcall(drawArt, mapID)
        if not drawn or not okArt then
            if art then art:Hide() end
            offerUntil = 0
            return nil
        end
    end
    local sl, st = physical(ns.StripFrame())
    local cl, ct, cw, ch = physical(art)
    if not sl or not cl then return nil end
    local r = function(v) return math.floor(v + 0.5) end
    return string.format("TW1|%d|%d|%d|%d|%d|%d", mapID, r(cl - sl), r(st - ct), r(cw), r(ch), (ThorCompanionDB and ThorCompanionDB.cell) or 3)
end
