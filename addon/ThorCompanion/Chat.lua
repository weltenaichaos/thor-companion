-- Chat.lua
-- Keeps the recent chat lines so the app's Chat tab can show them. Sent as
--   TH1|<session><tab><whisper names><newline><id><tab><kind><tab><sender><tab><channel><tab><text><newline>...
-- with only the lines the app has not been sent yet (after a refresh, the last
-- few again). <session> changes with every login or /reload, so the app knows
-- when the line numbers start over. <sender> is Name-Realm as the game gives it.
--
-- Tap to whisper: the last WHISPER_SLOTS people who wrote each get a key
-- (ALT-SHIFT-F1..F10) that opens the chat box with "/w Name ", so one tap on
-- their line in the app is one key press that starts a whisper; you type and
-- send it yourself. <whisper names> lists who has which key right now, comma
-- separated, so the app only offers the tap for names it knows the key of. Colour codes, links and icons are reduced to
-- plain text. During chat lockdown (boss fights) the game hides lines from
-- addons; those arrive as "(hidden by the game during combat)".
-- Public channels (General, Trade, ...) are left out unless /thor chat channels on.
--
-- Answering: four more keys open the chat box ready for a chat, like pressing
-- Enter and typing the command yourself: CTRL-SHIFT-F8 "/r " (the last whisper),
-- F9 "/s ", F10 "/g ", F11 your group ("/p ", or "/raid " or "/i " when in one).

local _, ns = ...

local KEEP = 60          -- lines remembered
local RESEND = 10        -- lines sent again on a refresh
local MAX_TEXT = 200     -- characters per line

local KINDS = {
    CHAT_MSG_SAY = "say", CHAT_MSG_YELL = "yell", CHAT_MSG_EMOTE = "emote", CHAT_MSG_TEXT_EMOTE = "emote",
    CHAT_MSG_WHISPER = "whisper", CHAT_MSG_WHISPER_INFORM = "whisper_to",
    CHAT_MSG_BN_WHISPER = "whisper", CHAT_MSG_BN_WHISPER_INFORM = "whisper_to",
    CHAT_MSG_PARTY = "party", CHAT_MSG_PARTY_LEADER = "party",
    CHAT_MSG_RAID = "raid", CHAT_MSG_RAID_LEADER = "raid", CHAT_MSG_RAID_WARNING = "raid",
    CHAT_MSG_INSTANCE_CHAT = "instance", CHAT_MSG_INSTANCE_CHAT_LEADER = "instance",
    CHAT_MSG_GUILD = "guild", CHAT_MSG_OFFICER = "guild",
    CHAT_MSG_SYSTEM = "system", CHAT_MSG_CHANNEL = "channel",
}

local WHISPER_SLOTS = 10

local lines = {}         -- { id, kind, sender, channel, text }
local nextId = 1
local sentUpTo = 0
local session = tostring(time())

local function secret(v)
    return issecretvalue and issecretvalue(v)
end

-- Colour codes, links and icons as plain text; one line, no tabs.
local function plain(s)
    s = s:gsub("|c%x%x%x%x%x%x%x%x", ""):gsub("|r", "")
    s = s:gsub("|H.-|h(.-)|h", "%1")
    s = s:gsub("|T.-|t", ""):gsub("|A.-|a", "")
    s = s:gsub("||", "|"):gsub("[\t\r\n]", " ")
    if #s > MAX_TEXT then s = s:sub(1, MAX_TEXT - 1) .. "…" end
    return s
end

-- Whisper keys: slot n is ALT-SHIFT-F<n>; a new name takes the slot used longest ago.
local whisperName = {}   -- slot -> Name-Realm
local whisperUsed = {}   -- slot -> counter when last written by that name
local whisperClock = 0
local whisperOwner = CreateFrame("Frame")
local whisperPending = false

local function remember(name)
    if name == "" or name == "?" or name == UnitName("player") then return end
    local slot
    for n = 1, WHISPER_SLOTS do
        if whisperName[n] == name then slot = n break end
    end
    if not slot then
        slot = 1
        for n = 1, WHISPER_SLOTS do
            if not whisperName[n] then slot = n break end
            if whisperUsed[n] < whisperUsed[slot] then slot = n end
        end
        whisperName[slot] = name
    end
    whisperClock = whisperClock + 1
    whisperUsed[slot] = whisperClock
end

local openedAt = -100
function ns.WhisperOpenedAt() return openedAt end

local function openWhisper(name)
    openedAt = GetTime()
    local tell = (ChatFrameUtil and ChatFrameUtil.SendTell) or ChatFrame_SendTell
    if tell then return tell(name) end
    local open = (ChatFrameUtil and ChatFrameUtil.OpenChat) or ChatFrame_OpenChat
    open("/w " .. name .. " ")
end

-- Plain buttons (whispering needs no secure code); the key may arrive as a
-- press and a release, the whisper opens on the first of them.
local whisperButtons = {}
local function whisperButton(n)
    local b = whisperButtons[n]
    if not b then
        b = CreateFrame("Button", "ThorCompanionWhisper" .. n, UIParent)
        b:RegisterForClicks("AnyUp", "AnyDown")
        local last = 0
        b:SetScript("OnClick", function()
            if GetTime() - last < 0.5 then return end
            last = GetTime()
            if whisperName[n] then openWhisper(whisperName[n]) end
        end)
        whisperButtons[n] = b
    end
    return b
end

-- The answer keys: which chat each opens.
local ANSWER = {
    [8] = function() return "/r " end,
    [9] = function() return "/s " end,
    [10] = function() return "/g " end,
    [11] = function()
        if IsInGroup(LE_PARTY_CATEGORY_INSTANCE) then return "/i " end
        if IsInRaid() then return "/raid " end
        return "/p "
    end,
}

local answerButtons = {}
local function answerButton(n)
    local b = answerButtons[n]
    if not b then
        b = CreateFrame("Button", "ThorCompanionAnswer" .. n, UIParent)
        b:RegisterForClicks("AnyUp", "AnyDown")
        local last = 0
        b:SetScript("OnClick", function()
            if GetTime() - last < 0.5 then return end
            last = GetTime()
            openedAt = GetTime()
            local open = (ChatFrameUtil and ChatFrameUtil.OpenChat) or ChatFrame_OpenChat
            open(ANSWER[n]())
        end)
        answerButtons[n] = b
    end
    return b
end

-- Binds the whisper and answer keys (out of combat only; otherwise once combat ends).
function ns.BindWhispers()
    if InCombatLockdown() then whisperPending = true return end
    whisperPending = false
    ClearOverrideBindings(whisperOwner)
    if not ns.TapsEnabled() then return end
    for n = 1, WHISPER_SLOTS do
        SetOverrideBindingClick(whisperOwner, true, ns.ActionKeys[ns.WhisperKeys[n]], whisperButton(n):GetName())
    end
    for n in pairs(ANSWER) do
        SetOverrideBindingClick(whisperOwner, true, ns.ActionKeys[ns.ChatKeys[n]], answerButton(n):GetName())
    end
end

whisperOwner:RegisterEvent("PLAYER_LOGIN")
whisperOwner:RegisterEvent("PLAYER_REGEN_ENABLED")
whisperOwner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" or whisperPending then ns.BindWhispers() end
end)

local function add(kind, sender, channel, text)
    if kind == "channel" and not (ThorCompanionDB and ThorCompanionDB.chatChannels) then return end
    if secret(text) then text = "(hidden by the game during combat)" else text = plain(tostring(text or "")) end
    if secret(sender) then sender = "?" else sender = plain(tostring(sender or "")) end
    -- "2. Trade - City" is shown as "Trade", like the chat frame does.
    channel = (not secret(channel) and channel) and plain(tostring(channel)):gsub("^%d+%.%s*", ""):gsub("%s+%-%s+.*$", "") or ""
    if kind ~= "system" and kind ~= "bnwhisper" then remember(sender) end
    lines[#lines + 1] = { nextId, kind, sender, channel, text }
    nextId = nextId + 1
    if #lines > KEEP then table.remove(lines, 1) end
end

local f = CreateFrame("Frame")
for event in pairs(KINDS) do f:RegisterEvent(event) end
f:SetScript("OnEvent", function(_, event, text, sender, _, channelName)
    local kind = KINDS[event]
    -- Battle.net whispers carry a protected name that /w cannot use.
    if event == "CHAT_MSG_BN_WHISPER" or event == "CHAT_MSG_BN_WHISPER_INFORM" then
        kind = kind == "whisper" and "bnwhisper" or "bnwhisper_to"
        sender = ""
    end
    add(kind, sender, kind == "channel" and channelName or "", text)
end)

-- The lines not sent yet (all that fit), or nil when there are none. With
-- again, the last RESEND lines (for an app that started late).
function ns.ChatPayload(again, peek)
    local from = again and math.max(1, nextId - RESEND) or sentUpTo + 1
    local room = ns.StripCapacity() - 32 - WHISPER_SLOTS * 30
    local out, used, last = {}, 0, nil
    for _, l in ipairs(lines) do
        if l[1] >= from then
            local entry = table.concat(l, "\t")
            if used + #entry + 1 > room then break end
            out[#out + 1] = entry
            used = used + #entry + 1
            last = l[1]
        end
    end
    if not last then return nil end
    if not peek then sentUpTo = math.max(sentUpTo, last) end
    local names = {}
    for n = 1, WHISPER_SLOTS do names[n] = whisperName[n] or "" end
    return "TH1|" .. session .. "\t" .. table.concat(names, ",") .. "\n" .. table.concat(out, "\n")
end

-- For /thor chat channels on|off.
function ns.ChatChannels(on)
    ThorCompanionDB.chatChannels = on
end
