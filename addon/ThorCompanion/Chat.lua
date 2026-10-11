-- Chat.lua
-- Keeps the recent chat lines so the app's Chat tab can show them. Sent as
--   TH1|<session><tab><whisper names><tab><channels><newline><id><tab><kind><tab><sender><tab><channel><tab><text><newline>...
-- with only the lines the app has not been sent yet (after a refresh, the last
-- few again). <session> changes with every login or /reload, so the app knows
-- when the line numbers start over. <sender> is Name-Realm as the game gives it.
--
-- <whisper names> lists the last people who wrote, comma separated. Colour codes, links and icons are reduced to
-- plain text. During chat lockdown (boss fights) the game hides lines from
-- addons; those arrive as "(hidden by the game during combat)".
-- <channels> are the public channels you are in, like "1 General,2 Trade", so the
-- app can offer to write in each. Their lines are sent too, unless /thor chat channels off.
--
-- Answering: CTRL-SHIFT-F8 is the game's own Open Chat key (like Enter). The app
-- presses it, waits for TE1 to say the box is open, then types "/w Name ", "/r ",
-- "/s ", "/g ", "/1 " (a channel) or your group's command with your message and presses Enter.

local _, ns = ...

local KEEP = 60          -- lines remembered
local KEEP_CHANNEL = 20  -- of those, at most this many from public channels (Trade can be busy)
local RESEND = 10        -- lines sent again on a refresh
local MAX_TEXT = 200     -- characters per line
local SENDS = 2          -- every line but a public channel's goes out twice, in case the app missed one

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
local sends = {}         -- id -> how often it went out
local nextId = 1
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
--   TE1|n|open|<group>|<length>   or   TE1|n|closed|<group>|<length>
-- n counts the times the box got the cursor; <group> is the command for your
-- group right now ("/p ", "/raid " or "/i "). The app types your message only
-- after "open", so no letter ever reaches the game as a key binding (W would walk).
-- <length> is how long the box's text is (in bytes): said again when it changes in
-- the first seconds after the box opened, so the app sees whether its paste arrived.
local openN, openPending, openedAt = 0, false, -100

local WATCH = 8   -- seconds after the box opened in which text changes are reported

local function editBox()
    local get = (ChatFrameUtil and ChatFrameUtil.GetActiveWindow) or ChatEdit_GetActiveWindow
    return get and get()
end

local function activeEditBox()
    local eb = editBox()
    return eb and eb:IsShown() and eb:HasFocus()
end

local function textLength()
    local eb = editBox()
    local text = eb and eb.GetText and eb:GetText()
    if type(text) ~= "string" or (issecretvalue and issecretvalue(text)) then return 0 end
    return #text
end

local function groupCommand()
    if IsInGroup(LE_PARTY_CATEGORY_INSTANCE) then return "/i " end
    if IsInRaid() then return "/raid " end
    return "/p "
end

function ns.ChatOpenPayload()
    if not openPending or GetTime() - openedAt < 0.1 then return nil end
    openPending = false
    return "TE1|" .. openN .. "|" .. (activeEditBox() and "open" or "closed") .. "|" .. groupCommand() .. "|" .. textLength()
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
            eb:HookScript("OnTextChanged", function()
                if GetTime() - openedAt < WATCH then openPending = true end
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
    if event == "PLAYER_LOGIN" then
        hookEditBoxes()
        -- 0.20.1 to 0.21.5 switched on the game's chat log (Logs/WoWChatLog.txt) for the
        -- app to read; the game writes it only every few minutes, so the chat is back on
        -- the square. Switched off again, once (a /chatlog of your own after that stays).
        if ThorCompanionDB and not ThorCompanionDB.chatLogOff then
            pcall(LoggingChat, false)
            ThorCompanionDB.chatLogOff = true
        end
    end
    if event == "PLAYER_LOGIN" or chatPending then ns.BindWhispers() end
end)

local function add(kind, sender, channel, text)
    if kind == "channel" and ThorCompanionDB and ThorCompanionDB.chatChannels == false then return end
    if secret(text) then text = "(hidden by the game during combat)" else text = plain(tostring(text or "")) end
    if secret(sender) then sender = "?" else sender = plain(tostring(sender or "")) end
    -- "2. Trade - City" is shown as "Trade", like the chat frame does.
    channel = (not secret(channel) and channel) and plain(tostring(channel)):gsub("^%d+%.%s*", ""):gsub("%s+%-%s+.*$", "") or ""
    if kind ~= "system" and kind ~= "bnwhisper" then remember(sender) end
    lines[#lines + 1] = { nextId, kind, sender, channel, text }
    nextId = nextId + 1
    -- Busy public channels make room among themselves, never by pushing out your
    -- group's, guild's or whisper lines.
    local channelLines, oldestChannel = 0, nil
    for i, l in ipairs(lines) do
        if l[2] == "channel" then
            channelLines = channelLines + 1
            oldestChannel = oldestChannel or i
        end
    end
    if channelLines > KEEP_CHANNEL or (#lines > KEEP and oldestChannel) then
        sends[lines[oldestChannel][1]] = nil
        table.remove(lines, oldestChannel)
    elseif #lines > KEEP then
        sends[lines[1][1]] = nil
        table.remove(lines, 1)
    end
    if kind ~= "channel" and kind ~= "system" and ns.ChatUrgent then ns.ChatUrgent() end
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

-- The public channels you are in: "1 General,2 Trade,...".
local function channels()
    local ok, list = pcall(function() return { GetChannelList() } end)
    if not ok then return "" end
    local out = {}
    for i = 1, #list - 1, 3 do
        local id, name, disabled = list[i], list[i + 1], list[i + 2]
        if not secret(id) and not secret(name) and tonumber(id) and name and not disabled then
            out[#out + 1] = tonumber(id) .. " " .. plain(tostring(name)):gsub("[,\t]", " ")
        end
    end
    return table.concat(out, ",")
end
local sentChannels = nil

-- The lines still to send (all that fit), or nil when there are none (and the
-- channels didn't change): your group's, guild's, whisper and other lines first,
-- each twice, then public channel lines once. In the order they were written (the
-- app sorts them by number). With again, the last RESEND lines (for an app that started late).
function ns.ChatPayload(again, peek)
    local chans = channels()
    local names = {}
    for n = 1, WHISPER_SLOTS do names[n] = whisperName[n] or "" end
    local head = "TH1|" .. session .. "\t" .. table.concat(names, ",") .. "\t" .. chans
    local room = ns.StripCapacity() - #head - 1
    local picked, used = {}, 0
    local function take(l)
        local entry = table.concat(l, "\t")
        -- A line too long for the square on its own is cut, or it would hold up the rest.
        if used == 0 and #entry + 1 > room then
            l[5] = l[5]:sub(1, math.max(0, #l[5] - (#entry + 1 - room) - 3)) .. "…"
            entry = table.concat(l, "\t")
        end
        if used + #entry + 1 > room then return false end
        picked[#picked + 1] = l
        used = used + #entry + 1
        return true
    end
    if again then
        for _, l in ipairs(lines) do
            if l[1] >= nextId - RESEND then take(l) end
        end
    else
        for _, l in ipairs(lines) do
            if l[2] ~= "channel" and (sends[l[1]] or 0) < SENDS and not take(l) then break end
        end
        for _, l in ipairs(lines) do
            if l[2] == "channel" and (sends[l[1]] or 0) < 1 and not take(l) then break end
        end
    end
    if #picked == 0 and chans == sentChannels and not again then return nil end
    table.sort(picked, function(a, b) return a[1] < b[1] end)
    local out = {}
    for i, l in ipairs(picked) do
        out[i] = table.concat(l, "\t")
        if not peek then sends[l[1]] = (sends[l[1]] or 0) + 1 end
    end
    if not peek then sentChannels = chans end
    return head .. "\n" .. table.concat(out, "\n")
end

-- Whether there are lines the app hasn't been sent yet.
function ns.ChatPending()
    for _, l in ipairs(lines) do
        if (sends[l[1]] or 0) < (l[2] == "channel" and 1 or SENDS) then return true end
    end
    return false
end

-- For /thor chat channels on|off.
function ns.ChatChannels(on)
    ThorCompanionDB.chatChannels = on
end
