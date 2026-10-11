-- Strip.lua
-- Draws the data for the companion app as a small square of dark grey cells in
-- the top-right corner (or, with /thor shape line, a thin line along the top
-- edge). The app reads the game's picture directly (no camera),
-- so the cells only need to differ a little: four shades close to black, two
-- bits per cell.
--
-- COLS x COLS cells, numbered left to right (and row by row, for the square):
--   cells 0..COLS-1  sync: lightest and darkest shade alternating, starting light
--   then, cell by cell:
--     8 cells     calibration: shades 0,1,2,3,0,1,2,3
--     4 bytes     header: format version, message number, part (index << 4 | count - 1), length
--     length      payload bytes
--     2 bytes     CRC-16/CCITT of the header and payload
--   Every byte is 4 cells, most significant bits first.
-- A message longer than one square is split into parts that are shown in turn
-- (twice after it changes, then the square stays still); the app puts them back
-- together. The companion app decodes this from a capture of the top screen.

local _, ns = ...

local COLS = 20
local VERSION = 4
local CALIB = 8
local PART_BYTES = math.floor(((COLS - 1) * COLS - CALIB) / 4) - 4 - 2   -- 87
local MAX_PARTS = 6
-- Two ways to go round (/thor speed fast|safe):
--  safe: every part 0.35 s, all parts twice; every message stays at least 1.5 s.
--  fast: every part 0.45 s, once. While messages follow each other (one went up in
--   the last BUSY_SECONDS) the app reads five times a second, so a single part stays
--   only 0.6 s; after a quiet spell the app looks once a second, so the first part of
--   the next message stays 1.1 s. A message the app still missed comes again when
--   the square is quiet (see Data.lua).
local SAFE = { step = 0.35, rounds = 2, min = 1.5 }
local FAST = { step = 0.45, rounds = 1, min = 0.6, first = 1.1 }
local BUSY_SECONDS = 2.5
local function speed() return (ThorCompanionDB and ThorCompanionDB.speed == "safe") and SAFE or FAST end
function ns.StripFast() return speed() == FAST end
local nextStepAt, lastDrawAt = 0, -100

local frame, cells
local msgSeq = 0
local message, parts, part, rounds = nil, {}, 1, 0
local stepper
local shownAt = -100

-- The same cells either as a COLS x COLS square or unrolled into one line
-- (COLS * COLS cells long, one cell high) along the top edge.
local function lineShape()
    return ThorCompanionDB and ThorCompanionDB.shape == "line"
end

local function settings()
    local db = ThorCompanionDB or {}
    return db.cell or 3, db.shade or 24, db.right or 0, db.top or (lineShape() and 0 or 22)
end

local function shade(level)
    local _, step = settings()
    local v = level * step / 255
    return v, v, v
end

local function setCell(i, level)
    local t = cells[i]
    if t then t:SetColorTexture(shade(level)) t:SetAlpha(1) end
end

local function crc16(s)
    local crc = 0xFFFF
    for i = 1, #s do
        crc = bit.bxor(crc, bit.lshift(s:byte(i), 8))
        for _ = 1, 8 do
            if bit.band(crc, 0x8000) ~= 0 then
                crc = bit.band(bit.bxor(bit.lshift(crc, 1), 0x1021), 0xFFFF)
            else
                crc = bit.band(bit.lshift(crc, 1), 0xFFFF)
            end
        end
    end
    return crc
end

local function place()
    local cell, _, right, top = settings()
    local _, h = GetPhysicalScreenSize()
    -- Ignoring the UI scale, with this scale 1 unit = 1 physical pixel.
    frame:SetScale(768 / h)
    local perRow = lineShape() and COLS * COLS or COLS
    frame:SetSize(perRow * cell, COLS * COLS / perRow * cell)
    frame:ClearAllPoints()
    frame:SetPoint("TOPRIGHT", UIParent, "TOPRIGHT", -right, -top)
    for i = 0, COLS * COLS - 1 do
        local t = cells[i]
        t:SetSize(cell, cell)
        t:ClearAllPoints()
        t:SetPoint("TOPLEFT", frame, "TOPLEFT", (i % perRow) * cell, -math.floor(i / perRow) * cell)
    end
end

-- Draws one part: the sync row, calibration, header, payload and checksum.
local function draw(index)
    local body = parts[index]
    local head = string.char(VERSION, msgSeq, (index - 1) * 16 + (#parts - 1), #body)
    local bytes = head .. body
    local crc = crc16(bytes)
    bytes = bytes .. string.char(bit.rshift(crc, 8), bit.band(crc, 255))
    for c = 0, COLS - 1 do setCell(c, c % 2 == 0 and 3 or 0) end
    local i = COLS
    for c = 0, CALIB - 1 do setCell(i, c % 4) i = i + 1 end
    for p = 1, #bytes do
        local b = bytes:byte(p)
        for s = 3, 0, -1 do setCell(i, bit.band(bit.rshift(b, s * 2), 3)) i = i + 1 end
    end
    for j = i, COLS * COLS - 1 do setCell(j, 0) end
end

local function build()
    -- Parented to UIParent (an unparented frame did not render on the Thor).
    frame = CreateFrame("Frame", "ThorCompanionStrip", UIParent)
    frame:SetIgnoreParentScale(true)
    frame:SetFrameStrata("TOOLTIP")
    frame:SetFrameLevel(9000)
    cells = {}
    for i = 0, COLS * COLS - 1 do
        local t = frame:CreateTexture(nil, "OVERLAY")
        t:SetSnapToPixelGrid(false)
        t:SetTexelSnappingBias(0)
        t:SetColorTexture(0, 0, 0, 1)
        cells[i] = t
    end
    place()
    frame:Show()
end

local function step()
    if #parts < 2 or rounds <= 0 or GetTime() < nextStepAt then return end
    nextStepAt = GetTime() + speed().step - 0.02
    lastDrawAt = GetTime()
    part = part + 1
    if part > #parts then
        part = 1
        rounds = rounds - 1
        if rounds <= 0 then part = #parts return end  -- stay on the last part shown
    end
    draw(part)
end

-- For /thor info: whether the square is on screen, and where.
function ns.StripInfo()
    if not cells then build() end
    local w, h = GetPhysicalScreenSize()
    local l, b, fw, fh = frame:GetRect()
    local cell, shadeStep, right, top = settings()
    return string.format("screen %dx%d, shown=%s visible=%s, rect=%s,%s %sx%s, cell=%d shade=%d right=%d top=%d",
        w, h, tostring(frame:IsShown()), tostring(frame:IsVisible()),
        tostring(l), tostring(b), tostring(fw), tostring(fh), cell, shadeStep, right, top)
end

-- The square's frame (for Map.lua, which measures the world map from it).
function ns.StripFrame()
    if not cells then build() end
    return frame
end

-- Largest message in bytes (split over up to MAX_PARTS parts).
function ns.StripCapacity()
    return PART_BYTES * MAX_PARTS
end

-- Bytes in one part: a message this short is shown at once, without parts going round.
function ns.StripPartBytes()
    return PART_BYTES
end

-- True while a message has not been up for its minimum time, or its parts are still
-- going round; the caller waits with the next one, so the app sees every part.
-- (Only waiting for the first round was too short: the app often first notices
-- a message halfway through it, and the facing in the status changes all the time.)
-- The status (your position) may go sooner: a newer one replaces it anyway.
local STATUS_SECONDS = 0.5

local holdFor
local minHold = SAFE.min
function ns.StripBusy()
    local hold = holdFor or (message and message:sub(1, 4) == "TS1|" and STATUS_SECONDS or minHold)
    return GetTime() - shownAt < hold or (#parts > 1 and rounds > 0)
end

-- hold: how long the message stays at least (default: see StripBusy).
function ns.StripWrite(payload, hold)
    if not cells then build() end
    if #payload > ns.StripCapacity() then payload = payload:sub(1, ns.StripCapacity()) end
    -- The same message again is left as it is, except the status: it goes out again
    -- now and then (with a new number) so the app knows the game is still there.
    if payload == message and payload:sub(1, 4) ~= "TS1|" then return end
    message = payload
    msgSeq = (msgSeq + 1) % 256
    parts = {}
    for p = 1, math.max(1, math.ceil(#payload / PART_BYTES)) do
        parts[p] = payload:sub((p - 1) * PART_BYTES + 1, p * PART_BYTES)
    end
    local sp = speed()
    local now = GetTime()
    local quiet = now - lastDrawAt > BUSY_SECONDS
    part, rounds = 1, sp.rounds
    shownAt, holdFor = now, hold
    minHold = (sp == FAST and quiet) and sp.first or sp.min
    nextStepAt = now + ((sp == FAST and quiet) and sp.first or sp.step) - 0.02
    lastDrawAt = now
    draw(1)
    if #parts > 1 and not stepper then stepper = C_Timer.NewTicker(0.05, step) end
end

-- Applies changed /thor cell, shade or position settings.
function ns.StripRefresh()
    if not cells then build() return end
    place()
    if message then draw(part) end
end

function ns.StripShow(show)
    if not cells then build() end
    frame:SetShown(show)
end
