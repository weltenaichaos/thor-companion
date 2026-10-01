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

local owner = CreateFrame("Frame")
local buttons = {}
local slotKey = {}     -- "bag:slot" -> index into ns.ActionKeys
local pending = false

local P = "|cff66ccffThor Companion|r "

local function button(i)
    local b = buttons[i]
    if not b then
        b = CreateFrame("Button", "ThorCompanionUse" .. i, UIParent, "SecureActionButtonTemplate")
        b:SetAttribute("type", "item")
        -- Diagnostics while tap-to-use is being tried out on the Thor: say what arrived.
        b:HookScript("OnClick", function(self, mouse, down)
            print(P .. "tap: " .. tostring(ns.ActionKeys[i]) .. " arrived for bag slot " ..
                tostring(self:GetAttribute("item")) .. " (" .. tostring(mouse) .. ", down=" .. tostring(down) .. ")")
        end)
        buttons[i] = b
    end
    -- Fire once per press, on whichever edge the game's own buttons use.
    b:RegisterForClicks(GetCVarBool("ActionButtonUseKeyDown") and "AnyDown" or "AnyUp")
    return b
end

local function enabled()
    return not (ThorCompanionDB and ThorCompanionDB.taps == false)
end

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
            local key = ns.ActionKeys[i]
            if not key then return end
            local b = button(i)
            b:SetAttribute("item", bag .. " " .. slot)
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
    return string.format("tap to use %s, %d bag slots have keys, %s does %s, use key down=%s%s",
        enabled() and "on" or "off", n, key, tostring(GetBindingAction(key, true)),
        tostring(GetCVarBool("ActionButtonUseKeyDown")), pending and ", waiting for combat to end" or "")
end

function ns.SetTaps(on)
    ThorCompanionDB.taps = on
    ns.BindSlots()
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
    end
end)
