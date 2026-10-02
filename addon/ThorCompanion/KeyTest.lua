-- KeyTest.lua
-- Checks which keys the companion app can send into the game. `/thor keytest`
-- binds the test keys below to a button that only prints the key's name; run it
-- again to give the keys back. Nothing here acts in the game. While the test runs,
-- the strip reports the keys that arrived (TK1|key,key,...) so the app can tick them off.

local _, ns = ...

-- On the Thor so far: CTRL-F5..F12 arrive (once the game keeps the key focus).
-- The others are tested again with the app's own key injection.
-- ALT-F4 is left out: it closes the game window.
ns.TestKeys = {}
for _, mod in ipairs({ "CTRL", "ALT" }) do
    for n = 1, 12 do
        if not (mod == "ALT" and n == 4) then ns.TestKeys[#ns.TestKeys + 1] = mod .. "-F" .. n end
    end
end

for _, key in ipairs({ "F9", "SHIFT-F9", "CTRL-SHIFT-F9", "NUMPAD5" }) do
    ns.TestKeys[#ns.TestKeys + 1] = key
end

local received, receivedList = {}, {}

local owner = CreateFrame("Frame")
local button = CreateFrame("Button", "ThorCompanionKeyTest", UIParent)
button:RegisterForClicks("AnyDown")
button:SetScript("OnClick", function(_, key)
    print("|cff66ccffThor Companion|r key test: got " .. tostring(key))
    if key and not received[key] then
        received[key] = true
        receivedList[#receivedList + 1] = key
    end
end)

local active = false

function ns.KeyTest()
    if InCombatLockdown() then
        print("|cff66ccffThor Companion|r key test: leave combat first")
        return
    end
    active = not active
    if active then
        received, receivedList = {}, {}
        for _, key in ipairs(ns.TestKeys) do
            SetOverrideBindingClick(owner, true, key, "ThorCompanionKeyTest", key)
        end
        print("|cff66ccffThor Companion|r key test on: tap the keys in the app's Keys tab. /thor keytest again to stop.")
    else
        ClearOverrideBindings(owner)
        print("|cff66ccffThor Companion|r key test off")
    end
end

-- What the strip shows while the test runs, or nil when it is off.
function ns.KeyTestPayload()
    if not active then return nil end
    return "TK1|" .. table.concat(receivedList, ",")
end
