-- Quests.lua
-- The app's Quests tab and its level checker on the Character tab:
--   TL1|lastKill|readyCount|readyXP|pages<newline><questID><tab><state><tab><xp><tab><title><tab><objective>;<objective>...<tab><level><tab><waypoint><tab><place><newline>...
-- lastKill is the experience the last kill gave without its rested bonus (0 until
-- there was one), readyCount and readyXP count all quests ready to turn in and the
-- experience they give. <state> is r (ready to turn in), z (in this zone) or o
-- (elsewhere); quests in this zone come first. A log too long for one square goes
-- out in pages: the rest as TL2 (see below), <pages> says how many there are. Objectives are the game's own text, like "4/8 Boar Ribs".
-- <level> is the quest's level (0 when unknown), for the colour of its title.
-- <waypoint> is where the game's own arrow points next, "mapID:x:y:zone" (empty
-- when the game doesn't say): for a quest ready to turn in that is whoever takes it,
-- also when they are in another zone. <place> is the town there (empty when none
-- is near), for the app's errands; without a waypoint, the town the quest's text
-- names for the turn-in.

local _, ns = ...

local MAX_TITLE = 40
local MAX_OBJECTIVE = 40

local function secret(v)
    return issecretvalue and issecretvalue(v)
end

local function number(v)
    if v == nil or secret(v) then return nil end
    return tonumber(v)
end

local function clean(s, max)
    if s == nil or secret(s) then return "" end
    s = tostring(s):gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", ""):gsub("[\t\r\n;|]", " ")
    if #s > max then s = s:sub(1, max - 1) .. "…" end
    return s
end

-- The last kills' experience: each time the experience can be read, what was gained
-- since the last reading, less the rested bonus (what the rested pool lost), is
-- shared out over the kills the game reported in between. Readings with a quest
-- turned in, or without kills, are left out. (In combat the game may hide the
-- experience from addons; then the next reading covers all kills of the fight.)
ThorCompanionKill = ThorCompanionKill  -- saved per character (see the .toc)
local lastXP, lastMax, lastRested, lastLevel
local kills, questsDone = 0, 0

local function measure()
    local xp, max = number(UnitXP("player")), number(UnitXPMax("player"))
    local rested = number(GetXPExhaustion()) or 0
    local level = number(UnitLevel("player"))
    if not xp or not max or not level then return end
    if lastXP and lastLevel and kills > 0 and questsDone == 0 then
        local gained = 0
        if level == lastLevel then gained = xp - lastXP
        elseif level == lastLevel + 1 then gained = lastMax - lastXP + xp end
        local base = gained - math.max(0, (lastRested or 0) - rested)
        if base > 0 then ThorCompanionKill = math.floor(base / kills + 0.5) end
    end
    lastXP, lastMax, lastRested, lastLevel = xp, max, rested, level
    kills, questsDone = 0, 0
end

local events = CreateFrame("Frame")
events:RegisterEvent("PLAYER_LOGIN")
events:RegisterEvent("PLAYER_XP_UPDATE")
events:RegisterEvent("PLAYER_LEVEL_UP")
events:RegisterEvent("PLAYER_REGEN_ENABLED")
events:RegisterEvent("CHAT_MSG_COMBAT_XP_GAIN")
events:RegisterEvent("QUEST_TURNED_IN")
events:SetScript("OnEvent", function(_, event)
    if event == "CHAT_MSG_COMBAT_XP_GAIN" then kills = kills + 1 return end
    if event == "QUEST_TURNED_IN" then questsDone = questsDone + 1 return end
    pcall(measure)
end)

local function rewardXP(id)
    local ok, xp = pcall(GetQuestLogRewardXP, id)
    return ok and number(xp) or 0
end

local function ready(id)
    local ok, r = pcall(C_QuestLog.ReadyForTurnIn, id)
    if ok and r then return true end
    ok, r = pcall(C_QuestLog.IsComplete, id)
    return ok and r == true
end

-- Where to go next for a quest, as the game's own arrow knows it: "mapID:x:y:zone"
-- (empty when the game doesn't say). This also covers quests whose next step, or
-- whose turn-in, is in another zone.
-- Also the town there, from the nearest flight master (empty when none is close).
-- The town the quest's own text names for handing it in ("Return to Zinge in the
-- Undercity." gives "Undercity"), for when the game gives no waypoint, which it doesn't
-- for many turn-ins in another zone, often one you haven't been to.
local function namedPlace(id)
    local index = C_QuestLog.GetLogIndexForQuestID and C_QuestLog.GetLogIndexForQuestID(id)
    if not index or not GetQuestLogCompletionText then return "" end
    local ok, text = pcall(GetQuestLogCompletionText, index)
    if not ok or type(text) ~= "string" or secret(text) then return "" end
    local place
    for p in text:gmatch(" in ([^%.,;!]+)") do place = p end
    if not place then return "" end
    place = place:gsub("^[Tt]he ", ""):gsub("%s+$", "")
    return place
end

local function waypoint(id, isReady)
    local ok, mapID, x, y = pcall(C_QuestLog.GetNextWaypoint, id)
    if not ok or not mapID or secret(mapID) or not x or not y then
        if not isReady then return "", "" end
        local okNamed, named = pcall(namedPlace, id)
        return "", okNamed and clean(named, 24) or ""
    end
    mapID, x, y = number(mapID), number(x), number(y)
    if not mapID or not x or not y then return "", "" end
    local info = C_Map.GetMapInfo(mapID)
    local okPlace, place = pcall(ns.PlaceName, mapID, x, y)
    return string.format("%d:%.3f:%.3f:%s", mapID, x, y, clean(info and info.name, 24)), okPlace and clean(place, 24) or ""
end

-- The whole log, cut into pages that fit the square: page 1 goes out as TL1 (with the
-- level checker's numbers), the others as
--   TL2|<page>|<pages><newline><quest rows like TL1's>...
-- Quests in this zone come first.
local function build()
    local here, elsewhere = {}, {}
    local readyCount, readyXP = 0, 0
    for i = 1, C_QuestLog.GetNumQuestLogEntries() or 0 do
        local info = C_QuestLog.GetInfo(i)
        if info and not info.isHeader and not info.isHidden and info.questID then
            local id = info.questID
            local xp = rewardXP(id)
            local isReady = ready(id)
            if isReady then
                readyCount = readyCount + 1
                readyXP = readyXP + xp
            end
            local objectives = {}
            for _, o in ipairs(C_QuestLog.GetQuestObjectives(id) or {}) do
                if o.text and not secret(o.text) and o.text ~= "" then
                    objectives[#objectives + 1] = (o.finished and "+" or "") .. clean(o.text, MAX_OBJECTIVE)
                end
            end
            local state = isReady and "r" or (info.isOnMap and "z" or "o")
            local level = number(info.difficultyLevel) or 0
            if level <= 0 then level = number(info.level) or 0 end
            local where, place = waypoint(id, isReady)
            local entry = table.concat({ id, state, xp, clean(info.title, MAX_TITLE), table.concat(objectives, ";"), level, where, place }, "\t")
            if state == "o" then elsewhere[#elsewhere + 1] = entry else here[#here + 1] = entry end
        end
    end
    local pages, page, used = {}, {}, 0
    local room = ns.StripCapacity() - 40   -- room for either head
    for _, list in ipairs({ here, elsewhere }) do
        for _, e in ipairs(list) do
            if #page > 0 and used + #e + 1 > room then
                pages[#pages + 1] = page
                page, used = {}, 0
            end
            if #e + 1 <= room then
                page[#page + 1] = e
                used = used + #e + 1
            end
        end
    end
    pages[#pages + 1] = page
    return string.format("TL1|%d|%d|%d|%d", ThorCompanionKill or 0, readyCount, readyXP, #pages), pages
end

function ns.QuestsPayload()
    local head, pages = build()
    return head .. (#pages[1] > 0 and ("\n" .. table.concat(pages[1], "\n")) or "")
end

-- The next page after the first that the app hasn't had twice since it changed, or nil.
local sentPage, sentCount = {}, {}
local pagesSum = 0

-- The checksum (as TS1's) over the pages after the first, one after another, as last
-- worked out: 0 while there is only one page.
function ns.QuestPagesSum()
    return pagesSum
end

function ns.QuestPagesPayload()
    local _, pages = build()
    local all = {}
    for n = 2, #pages do all[#all + 1] = "TL2|" .. n .. "|" .. #pages .. "\n" .. table.concat(pages[n], "\n") end
    local h = 0
    local joined = table.concat(all)
    for i = 1, #joined do h = (h * 31 + joined:byte(i)) % 65536 end
    pagesSum = h
    for n = 2, #pages do
        local p = all[n - 1]
        if p ~= sentPage[n] then sentPage[n], sentCount[n] = p, 0 end
        if sentCount[n] < 2 then
            sentCount[n] = sentCount[n] + 1
            return p
        end
    end
    return nil
end

-- Every page again (a refresh, or an app that just started).
function ns.QuestPagesAgain()
    sentPage, sentCount = {}, {}
end
