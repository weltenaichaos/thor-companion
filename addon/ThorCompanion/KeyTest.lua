-- KeyTest.lua
-- Checks which keys the companion app can send into the game. `/thor keytest`
-- binds the test keys below to a button that only prints the key's name; run it
-- again to give the keys back. Nothing here acts in the game.

local _, ns = ...

ns.TestKeys = {
    "F9", "CTRL-F9", "SHIFT-F9", "ALT-F9", "CTRL-SHIFT-F9",
    "NUMPAD5", "CTRL-NUMPAD5",
}

local owner = CreateFrame("Frame")
local button = CreateFrame("Button", "ThorCompanionKeyTest", UIParent)
button:RegisterForClicks("AnyDown")
button:SetScript("OnClick", function(_, key)
    print("|cff66ccffThor Companion|r key test: got " .. tostring(key))
end)

local active = false

function ns.KeyTest()
    if InCombatLockdown() then
        print("|cff66ccffThor Companion|r key test: leave combat first")
        return
    end
    active = not active
    if active then
        for _, key in ipairs(ns.TestKeys) do
            SetOverrideBindingClick(owner, true, key, "ThorCompanionKeyTest", key)
        end
        print("|cff66ccffThor Companion|r key test on: tap the keys in the app's Keys tab. /thor keytest again to stop.")
    else
        ClearOverrideBindings(owner)
        print("|cff66ccffThor Companion|r key test off")
    end
end
