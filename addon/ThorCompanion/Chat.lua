-- Chat.lua
-- Keeps the recent chat lines so the app's Chat tab can show them. Sent as
--   TH1|<session><newline><id><tab><kind><tab><sender><tab><text><newline>...
-- with only the lines the app has not been sent yet (after a refresh, the last
-- few again). <session> changes with every login or /reload, so the app knows
-- when the line numbers start over. Colour codes, links and icons are reduced to
-- plain text. During chat lockdown (boss fights) the game hides lines from
-- addons; those arrive as "(hidden by the game during combat)".
-- Public channels (General, Trade, ...) are left out unless /thor chat channels on.

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

local lines = {}         -- { id, kind, sender, text }
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

local function add(kind, sender, text)
    if secret(text) then text = "(hidden by the game during combat)" else text = plain(tostring(text or "")) end
    if secret(sender) then sender = "?" else sender = plain(tostring(sender or "")):gsub("%-.*$", "") end
    if kind == "channel" and not (ThorCompanionDB and ThorCompanionDB.chatChannels) then return end
    lines[#lines + 1] = { nextId, kind, sender, text }
    nextId = nextId + 1
    if #lines > KEEP then table.remove(lines, 1) end
end

local f = CreateFrame("Frame")
for event in pairs(KINDS) do f:RegisterEvent(event) end
f:SetScript("OnEvent", function(_, event, text, sender, _, channelName)
    local kind = KINDS[event]
    -- A channel line shows which channel it is in front of the sender, like the chat frame.
    if kind == "channel" and not secret(channelName) and channelName and channelName ~= "" then
        sender = "[" .. tostring(channelName):gsub("^%d+%.%s*", "") .. "] " .. (secret(sender) and "?" or tostring(sender or ""))
    end
    add(kind, sender, text)
end)

-- The lines not sent yet (all that fit), or nil when there are none. With
-- again, the last RESEND lines (for an app that started late).
function ns.ChatPayload(again, peek)
    local from = again and math.max(1, nextId - RESEND) or sentUpTo + 1
    local room = ns.StripCapacity() - 32
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
    return "TH1|" .. session .. "\n" .. table.concat(out, "\n")
end

-- For /thor chat channels on|off.
function ns.ChatChannels(on)
    ThorCompanionDB.chatChannels = on
end
