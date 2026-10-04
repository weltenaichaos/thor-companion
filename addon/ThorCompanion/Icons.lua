-- Icons.lua
-- Item icons for the app's bag and gear tiles. The app can't read the game's
-- files, so like the zone picture it takes them off the screen: its "Get item
-- icons" button presses one key (CTRL-SHIFT-F12), and the addon shows the icons
-- of your bag and worn items in a grid in the middle of the screen for a few
-- seconds, while
--   TI1|left|top|size|step|cols|cell|itemID,itemID,...
-- says where the first icon is (game pixels from the top-left corner of the data
-- square), how big each icon is, the distance from one to the next, how many
-- there are per row, and which item each one is, row by row. The app cuts them
-- out of one screenshot and keeps them. A press shows up to PAGE icons; the next
-- press shows the next ones.

local _, ns = ...

local SECONDS = 5
local SIZE, GAP, COLS = 40, 8, 12
local PAGE = 60
local frame, textures = nil, {}
local shownUntil, shownIDs, page = 0, nil, 0
local note = "no icons asked for yet"

local function physical(f)
    local l, b, w, h = f:GetRect()
    if not l then return end
    local k = f:GetEffectiveScale() * select(2, GetPhysicalScreenSize()) / 768
    return l * k, (b + h) * k, w * k, h * k, k
end

-- Bag items first, then what you wear, each item once.
local function allIDs()
    local out, seen = {}, {}
    for _, list in ipairs({ ns.BagIDs(), ns.EquippedIDs() }) do
        for _, id in ipairs(list) do
            if not seen[id] then seen[id] = true out[#out + 1] = id end
        end
    end
    return out
end

local function draw(ids)
    if not frame then
        frame = CreateFrame("Frame", nil, UIParent)
        frame:SetFrameStrata("FULLSCREEN_DIALOG")
        -- Like the zone picture: 1 unit = 1 game pixel, whatever the UI scale.
        frame:SetIgnoreParentScale(true)
        local bg = frame:CreateTexture(nil, "BACKGROUND")
        bg:SetAllPoints()
        bg:SetColorTexture(0, 0, 0, 1)
    end
    for _, t in ipairs(textures) do t:Hide() end
    local step = SIZE + GAP
    local rows = math.ceil(#ids / COLS)
    frame:SetScale(768 / select(2, GetPhysicalScreenSize()))
    frame:SetSize(GAP + math.min(#ids, COLS) * step, GAP + rows * step)
    frame:ClearAllPoints()
    frame:SetPoint("CENTER", UIParent, "CENTER", 0, 0)
    for i, id in ipairs(ids) do
        local t = textures[i]
        if not t then
            t = frame:CreateTexture(nil, "ARTWORK")
            -- Without the frame the game draws around every icon.
            t:SetTexCoord(0.08, 0.92, 0.08, 0.92)
            textures[i] = t
        end
        t:SetTexture(C_Item.GetItemIconByID(id) or 134400)  -- 134400: the question mark
        t:SetSize(SIZE, SIZE)
        t:ClearAllPoints()
        t:SetPoint("TOPLEFT", frame, "TOPLEFT", GAP + ((i - 1) % COLS) * step, -(GAP + math.floor((i - 1) / COLS) * step))
        t:Show()
    end
    frame:Show()
end

-- The key: show the next page of icons.
function ns.IconsShow()
    local all = allIDs()
    if #all == 0 then note = "no items to show" return end
    page = page + 1
    if (page - 1) * PAGE >= #all then page = 1 end
    local ids = {}
    for i = (page - 1) * PAGE + 1, math.min(#all, page * PAGE) do ids[#ids + 1] = all[i] end
    local ok, err = pcall(draw, ids)
    if not ok then
        note = "could not draw the icons: " .. tostring(err)
        if frame then frame:Hide() end
        return
    end
    shownIDs, shownUntil = ids, GetTime() + SECONDS
    note = string.format("showed icons %d to %d of %d at %s", (page - 1) * PAGE + 1, (page - 1) * PAGE + #ids, #all, date("%H:%M:%S"))
end

-- TI1 while the icons are up, else nil (and the icons go away).
function ns.IconsPayload()
    if GetTime() > shownUntil then
        if frame and frame:IsShown() then frame:Hide() end
        return nil
    end
    local sl, st = physical(ns.StripFrame())
    local cl, ct, _, _, k = physical(frame)
    if not sl or not cl then return nil end
    local r = function(v) return math.floor(v + 0.5) end
    local head = string.format("TI1|%d|%d|%d|%d|%d|%d|", r(cl - sl + GAP * k), r(st - ct + GAP * k),
        r(SIZE * k), r((SIZE + GAP) * k), COLS, (ThorCompanionDB and ThorCompanionDB.cell) or 3)
    return head .. table.concat(shownIDs, ",")
end

function ns.IconsInfo()
    return "item icons: " .. note
end

-- The icon key, bound out of combat like the others (and off with /thor taps off).
local keyOwner = CreateFrame("Frame")
local keyButton = CreateFrame("Button", "ThorCompanionIcons", UIParent)
keyButton:RegisterForClicks("AnyUp", "AnyDown")
local lastClick = 0
keyButton:SetScript("OnClick", function()
    if GetTime() - lastClick < 0.5 then return end
    lastClick = GetTime()
    ns.IconsShow()
end)
local keyPending = false

function ns.BindIconKey()
    if InCombatLockdown() then keyPending = true return end
    keyPending = false
    ClearOverrideBindings(keyOwner)
    if ns.TapsEnabled() then
        SetOverrideBindingClick(keyOwner, true, ns.ActionKeys[ns.IconKey], keyButton:GetName())
    end
end

keyOwner:RegisterEvent("PLAYER_LOGIN")
keyOwner:RegisterEvent("PLAYER_REGEN_ENABLED")
keyOwner:SetScript("OnEvent", function(_, event)
    if event == "PLAYER_LOGIN" or keyPending then ns.BindIconKey() end
end)
