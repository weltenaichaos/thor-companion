-- Spells.lua
-- Which class spells you can learn soon, for the app's level card:
--   TV1|level<newline><level><tab><state><tab><cost><tab><name><tab><rank><newline>...
-- <level> is the level the spell needs, <state> r when your class trainer offers it
-- to you now, f when it comes at a later level. <cost> is the trainer's price in
-- copper (0 when not known). Sorted by level; spells you already know are left out.
--
-- Where it comes from, whatever this game allows:
--   - your class trainer's list, read each time you open it (kept per character, so
--     the app knows it from then on; the trainer is the only one that says the cost),
--   - the spellbook's not-yet-learned spells, with the level they come at,
--   - the spells the game says come with the next level.

local _, ns = ...

local AHEAD = 2   -- future spells up to this many levels ahead

local function secret(v)
    return issecretvalue and issecretvalue(v)
end

local function number(v)
    if v == nil or secret(v) then return nil end
    return tonumber(v)
end

local function clean(s)
    if s == nil or secret(s) then return "" end
    s = tostring(s):gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", ""):gsub("[\t\r\n|]", " ")
    if #s > 32 then s = s:sub(1, 31) .. "…" end
    return s
end

local function who()
    return (UnitName("player") or "?") .. "-" .. (GetRealmName and GetRealmName() or "")
end

-- The class trainer's list, while its window is open.
local function readTrainer()
    if IsTradeskillTrainer and IsTradeskillTrainer() then return end
    local list = {}
    for i = 1, GetNumTrainerServices() or 0 do
        local name, rank, category = GetTrainerServiceInfo(i)
        if name and category ~= "header" and category ~= "used" then
            list[#list + 1] = {
                name = clean(name), rank = clean(rank),
                level = number(GetTrainerServiceLevelReq(i)) or 0,
                cost = number(GetTrainerServiceCost(i)) or 0,
            }
        end
    end
    ThorCompanionDB.trainer = ThorCompanionDB.trainer or {}
    ThorCompanionDB.trainer[who()] = list
end

-- Worked out again only when something changed (the spellbook is long).
local cached, dirty = nil, true

local events = CreateFrame("Frame")
for _, e in ipairs({ "TRAINER_SHOW", "TRAINER_UPDATE", "PLAYER_LEVEL_UP", "SPELLS_CHANGED", "PLAYER_LOGIN" }) do
    events:RegisterEvent(e)
end
events:SetScript("OnEvent", function(_, event)
    if event == "TRAINER_SHOW" or event == "TRAINER_UPDATE" then pcall(readTrainer) end
    dirty = true
end)

local function add(found, level, name, rank, cost)
    if not level or level <= 0 or name == "" then return end
    local key = name .. "\t" .. (rank or "")
    local had = found[key]
    if had then
        if cost and cost > 0 then had.cost = cost end
        return
    end
    found[key] = { level = level, name = name, rank = rank or "", cost = cost or 0 }
end

-- The spellbook's spells you don't have yet (shown greyed out in the game).
local function fromSpellbook(found, upTo)
    local future = Enum and Enum.SpellBookItemType and Enum.SpellBookItemType.FutureSpell
    if not future or not C_SpellBook or not C_SpellBook.GetNumSpellBookSkillLines then return end
    local bank = Enum.SpellBookSpellBank and Enum.SpellBookSpellBank.Player or 0
    for line = 1, C_SpellBook.GetNumSpellBookSkillLines() or 0 do
        local info = C_SpellBook.GetSpellBookSkillLineInfo(line)
        if info and not info.isGuild and not info.shouldHide then
            for slot = info.itemIndexOffset + 1, info.itemIndexOffset + info.numSpellBookItems do
                local item = C_SpellBook.GetSpellBookItemInfo(slot, bank)
                if item and item.itemType == future then
                    local level = number(C_SpellBook.GetSpellBookItemLevelLearned(slot, bank))
                    if level and level <= upTo then add(found, level, clean(item.name), clean(item.subName)) end
                end
            end
        end
    end
end

-- The spells the game says come with a level.
local function fromLevels(found, from, upTo)
    if not C_SpellBook or not C_SpellBook.GetCurrentLevelSpells then return end
    for level = from, upTo do
        for _, id in ipairs(C_SpellBook.GetCurrentLevelSpells(level) or {}) do
            if not (C_SpellBook.IsSpellKnown and C_SpellBook.IsSpellKnown(id)) then
                local name = C_Spell and C_Spell.GetSpellName and C_Spell.GetSpellName(id)
                local sub = C_Spell and C_Spell.GetSpellSubtext and C_Spell.GetSpellSubtext(id)
                add(found, level, clean(name), clean(sub))
            end
        end
    end
end

local function payload()
    local level = number(UnitLevel("player"))
    if not level then return nil end
    local upTo = level + AHEAD
    local found = {}
    -- The trainer first: it alone knows the cost (the others only fill in).
    local kept = ThorCompanionDB and ThorCompanionDB.trainer and ThorCompanionDB.trainer[who()] or {}
    for _, s in ipairs(kept) do
        if s.level <= upTo then add(found, s.level, s.name, s.rank, s.cost) end
    end
    pcall(fromSpellbook, found, upTo)
    pcall(fromLevels, found, level + 1, upTo)
    local list = {}
    for _, s in pairs(found) do list[#list + 1] = s end
    table.sort(list, function(a, b)
        if a.level ~= b.level then return a.level < b.level end
        return a.name < b.name
    end)
    local head = "TV1|" .. level
    local room = ns.StripCapacity() - #head - 1
    local out, used = {}, 0
    for _, s in ipairs(list) do
        local row = table.concat({ s.level, s.level <= level and "r" or "f", s.cost, s.name, s.rank }, "\t")
        if used + #row + 1 > room then break end
        out[#out + 1] = row
        used = used + #row + 1
    end
    return head .. "\n" .. table.concat(out, "\n")
end

function ns.SpellsPayload()
    if dirty then
        local ok, p = pcall(payload)
        cached, dirty = ok and p or cached, false
    end
    return cached
end
