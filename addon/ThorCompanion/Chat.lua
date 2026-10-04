-- Chat.lua
-- Keeps the recent chat lines so the app's Chat tab can show them. Sent as
--   TH1|<session><tab><whisper names><newline><id><tab><kind><tab><sender><tab><channel><tab><text><newline>...
-- with only the lines the app has not been sent yet (after a refresh, the last
-- few again). <session> changes with every login or /reload, so the app knows
-- when the line numbers start over. <sender> is Name-Realm as the game gives it.
--
-- <whisper names> lists the last people who wrote, comma separated. Colour codes, links and icons are reduced to
-- plain text. During chat lockdown (boss fights) the game hides lines from
-- addons; those arrive as "(hidden by the game during combat)".
-- Public channels (General, Trade, ...) are left out unless /thor chat channels on.
--
-- Answering: CTRL-SHIFT-F8 is the game's own Open Chat key (like Enter). The app
-- presses it, waits for TE1 to say the box is open, then types "/w Name ", "/r ",
-- "/s ", "/g " or your group's command with your message and presses Enter.

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

-- Who wrote last: the app offers a whisper to these names (the last WHISPER_SLOTS).
local whisperName = {}   -- slot -> Name-Realm
local whisperUsed = {}   -- slot -> counter when last written by that name
local whisperClock = 0
local chatOwner = CreateFrame("Frame")
local chatPending = false

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

-- Whether the chat box opened, for the app:
--   TE1|n|open|<group>   or   TE1|n|closed|<group>
-- n counts the times the box got the cursor; <group> is the command for your
-- group right now ("/p ", "/raid " or "/i "). The app types your message only
-- after "open", so no letter ever reaches the game as a key binding (W would walk).
local openN, openPending, openedAt = 0, false, -100

local function activeEditBox()
    local get = (ChatFrameUtil and ChatFrameUtil.GetActiveWindow) or ChatEdit_GetActiveWindow
    local eb = get and get()
    return eb and eb:IsShown() and eb:HasFocus()
end

local function groupCommand()
    if IsInGroup(LE_PARTY_CATEGORY_INSTANCE) then return "/i " end
    if IsInRaid() then return "/raid " end
    return "/p "
end

function ns.ChatOpenPayload()
    if not openPending or GetTime() - openedAt < 0.1 then return nil end
    openPending = false
    return "TE1|" .. openN .. "|" .. (activeEditBox() and "open" or "closed") .. "|" .. groupCommand()
end

-- Only watched, never called: an addon that opens the chat box itself makes the
-- game block its controller code (the "blocked from an action" popup).
local hooked = {}
local function hookEditBoxes()
    for i = 1, (NUM_CHAT_WINDOWS or 10) do
        local eb = _G["ChatFrame" .. i .. "EditBox"]
        if eb and not hooked[eb] then
            hooked[eb] = true
            eb:HookScript("OnEditFocusGained", function()
                openN, openPending, openedAt = openN + 1, true, GetTime()
            end)
        end
    end
end

-- The chat key (CTRL-SHIFT-F8) is bound to the game's own "Open Chat" command,
-- exactly like Enter; the app then types "/g ", "/w Name " and so on with your text.
function ns.BindWhispers()
    if InCombatLockdown() then chatPending = true return end
    chatPending = false
    ClearOverrideBindings(chatOwner)
    if not ns.TapsEnabled() then return end
    SetOverrideBinding(chatOwner, true, ns.ActionKeys[ns.ChatKeys[8]], "OPENCHAT")
end

chatOwner:RegisterEvent("PLAYER_LOGIN")
chatOwner:RegisterEvent("PLAYER_REGEN_ENABLED")
chatOwner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" then hookEditBoxes() end
    if event == "PLAYER_LOGIN" or chatPending then ns.BindWhispers() end
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
