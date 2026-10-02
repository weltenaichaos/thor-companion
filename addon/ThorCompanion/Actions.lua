-- Actions.lua
-- Tap to use: every bag slot gets a secure button that uses whatever item is in
-- that slot, and its own key. The companion app sends that one key when a bag
-- tile is tapped, so one tap is one key press is one use, as if you had clicked
-- the item yourself. Bindings can only change out of combat; they are set at
-- login and whenever the bags change size, and stay valid in combat.
-- `/thor taps off` removes them (and `/thor taps on` brings them back).

local _, ns = ...

-- The keys, in the order the app also uses (decoder ActionKeys.kt): the ones
-- tested on the Thor first. ALT-F4 is left out: it closes the game window.
ns.ActionKeys = {}
do
    local mods = { "CTRL", "ALT", "CTRL-SHIFT", "SHIFT", "ALT-SHIFT" }
    for _, mod in ipairs(mods) do
        for n = 1, 12 do
            local key = mod .. "-F" .. n
            if key ~= "ALT-F4" then ns.ActionKeys[#ns.ActionKeys + 1] = key end
        end
    end
    for _, mod in ipairs(mods) do
        for n = 0, 9 do ns.ActionKeys[#ns.ActionKeys + 1] = mod .. "-NUMPAD" .. n end
    end
end

-- ALT-SHIFT-F1..F10 open whispers (Chat.lua); bag slots get the other keys.
ns.WhisperKeys = {}
local reserved = {}
for i, key in ipairs(ns.ActionKeys) do
    local n = tonumber(key:match("^ALT%-SHIFT%-F(%d+)$"))
    if n and n <= 10 then ns.WhisperKeys[n] = i reserved[i] = true end
end

local owner = CreateFrame("Frame")
local buttons = {}
local slotKey = {}     -- "bag:slot" -> index into ns.ActionKeys
local pending = false

local P = "|cff66ccffThor Companion|r "

-- Points a slot's button at its item, or at nothing when the slot is empty: the
-- game's item button raises a Lua error when told to use an empty slot.
local function aim(b, bag, slot)
    local info = C_Container.GetContainerItemInfo(bag, slot)
    b:SetAttribute("item", info and (bag .. " " .. slot) or nil)
end

local function button(i)
    local b = buttons[i]
    if not b then
        b = CreateFrame("Button", "ThorCompanionUse" .. i, UIParent, "SecureActionButtonTemplate")
        b:SetAttribute("type", "item")
        -- Use the item on key release, always. Otherwise the game decides by the
        -- ActionButtonUseKeyDown setting, which can differ from what it was when the
        -- button was made, and then ignores the edge that arrives (seen on the Thor).
        b:SetAttribute("useOnKeyDown", false)
        b:RegisterForClicks("AnyUp", "AnyDown")
        buttons[i] = b
    end
    return b
end

local function enabled()
    return not (ThorCompanionDB and ThorCompanionDB.taps == false)
end
ns.TapsEnabled = enabled

-- Binds one key per bag slot. Only out of combat; otherwise it waits until combat ends.
function ns.BindSlots()
    if InCombatLockdown() then pending = true return end
    pending = false
    ClearOverrideBindings(owner)
    slotKey = {}
    if not enabled() then return end
    local i = 0
    for bag = 0, 4 do
        for slot = 1, C_Container.GetContainerNumSlots(bag) or 0 do
            i = i + 1
            while reserved[i] do i = i + 1 end
            local key = ns.ActionKeys[i]
            if not key then return end
            local b = button(i)
            aim(b, bag, slot)
            SetOverrideBindingClick(owner, true, key, b:GetName())
            slotKey[bag .. ":" .. slot] = i
        end
    end
end

-- The key index bound to a bag slot, or nil.
function ns.SlotKey(bag, slot)
    return slotKey[bag .. ":" .. slot]
end

-- For `/thor taps`: whether the keys are set up, and what the first one does.
function ns.TapsInfo()
    local n = 0
    for _ in pairs(slotKey) do n = n + 1 end
    local key = ns.ActionKeys[1]
    return string.format("tap to use %s, %d bag slots have keys, %s does %s, game uses keys on press=%s%s",
        enabled() and "on" or "off", n, key, tostring(GetBindingAction(key, true)),
        tostring(GetCVarBool("ActionButtonUseKeyDown")), pending and ", waiting for combat to end" or "")
end

function ns.SetTaps(on)
    ThorCompanionDB.taps = on
    ns.BindSlots()
    ns.BindWhispers()
end

local sizes = ""
owner:RegisterEvent("PLAYER_LOGIN")
owner:RegisterEvent("BAG_UPDATE_DELAYED")
owner:RegisterEvent("PLAYER_REGEN_ENABLED")
owner:RegisterEvent("ADDON_ACTION_BLOCKED")
owner:RegisterEvent("ADDON_ACTION_FORBIDDEN")
owner:SetScript("OnEvent", function(_, event, ...)
    if event == "ADDON_ACTION_BLOCKED" or event == "ADDON_ACTION_FORBIDDEN" then
        local addon, fn = ...
        -- Opening the chat box for a whisper runs the game's own controller code,
        -- which then may not pick a new interact target. The whisper works, so
        -- that one is not worth a notice (seen on the Thor).
        if GetTime() - ns.WhisperOpenedAt() < 1 then return end
        if addon == "ThorCompanion" then print(P .. "the game blocked " .. tostring(fn) .. " (" .. event .. ")") end
        return
    end
    if event == "PLAYER_REGEN_ENABLED" then
        if pending then ns.BindSlots() end
        return
    end
    -- Rebind only when the slot layout changed (a bag added or swapped), not on every loot.
    local now = {}
    for bag = 0, 4 do now[#now + 1] = C_Container.GetContainerNumSlots(bag) or 0 end
    now = table.concat(now, ",")
    if event == "PLAYER_LOGIN" or now ~= sizes then
        sizes = now
        ns.BindSlots()
    elseif InCombatLockdown() then
        pending = true -- an item may have run out; re-aim the buttons after combat
    else
        for bag = 0, 4 do
            for slot = 1, C_Container.GetContainerNumSlots(bag) or 0 do
                local i = slotKey[bag .. ":" .. slot]
                if i then aim(buttons[i], bag, slot) end
            end
        end
    end
end)
