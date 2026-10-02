-- Strip.lua
-- Draws a small block of 5x5-pixel colour cells in the bottom-right corner, a
-- little above the edge. The Thor's display path shifts colours a lot (pure green shows
-- as 117,251,76), so cells carry 6 bits each as an index into a 64-colour
-- palette (4 levels per channel), and every frame shows the whole palette so
-- the decoder can calibrate against what actually reached the screen.
--
-- Cells are numbered left to right, bottom row first (COLS cells per row):
--   0-3     sync: magenta, green, magenta, green
--   4-67    calibration: palette colours 0..63 in order
--   68      sequence number (0-63)
--   69      format version
--   70-71   payload length in bytes (12 bits)
--   72..    payload, 6 bits per cell, MSB first
--   then 3  CRC-16/CCITT of the payload (18 bits, top 2 zero)
-- The companion app decodes this from a capture of the top screen.

local _, ns = ...

local CELL = 5          -- physical pixels per cell edge; about 7.5 screen pixels on the Thor
local COLS = 48         -- cells per row: a fixed width, so the decoder knows the layout
local ROWS = 14         -- 672 cells, room for 423 bytes as before
local VERSION = 3
local DATA = 72

local frame, cells, numCells, perRow
local seq = 0
local shown  -- the payload on screen now; the same payload again changes nothing

local function setCell(i, sym)
    local t = cells[i]
    if not t then return end
    local r, g, b = math.floor(sym / 16) % 4, math.floor(sym / 4) % 4, sym % 4
    t:SetColorTexture(r / 3, g / 3, b / 3, 1)
end

local function setRaw(i, r, g, b)
    cells[i]:SetColorTexture(r, g, b, 1)
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

local function build()
    local _, h = GetPhysicalScreenSize()
    -- Parented to UIParent (an unparented frame did not render on the Thor),
    -- ignoring its UI scale: with this scale 1 unit = 1 physical pixel.
    frame = CreateFrame("Frame", "ThorCompanionStrip", UIParent)
    frame:SetIgnoreParentScale(true)
    frame:SetScale(768 / h)
    frame:SetFrameStrata("TOOLTIP")
    frame:SetFrameLevel(9000)
    frame:ClearAllPoints()
    -- On the Thor the bottom ~15 rows of the 720-row picture are cut off by
    -- the display, so the block sits a little above the edge.
    frame:SetPoint("BOTTOMRIGHT", UIParent, "BOTTOMRIGHT", 0, ns.StripOffset or 32)
    perRow = COLS
    numCells = perRow * ROWS
    frame:SetSize(perRow * CELL, ROWS * CELL)
    cells = {}
    for i = 0, numCells - 1 do
        local t = frame:CreateTexture(nil, "OVERLAY")
        t:SetSnapToPixelGrid(false)
        t:SetTexelSnappingBias(0)
        t:SetSize(CELL, CELL)
        t:SetPoint("BOTTOMLEFT", frame, "BOTTOMLEFT", (i % perRow) * CELL, math.floor(i / perRow) * CELL)
        t:SetColorTexture(0, 0, 0, 1)
        cells[i] = t
    end
    setRaw(0, 1, 0, 1); setRaw(1, 0, 1, 0); setRaw(2, 1, 0, 1); setRaw(3, 0, 1, 0)
    for p = 0, 63 do setCell(4 + p, p) end
    frame:Show()
end

-- For /thor info: whether the strip is on screen, and where.
function ns.StripInfo()
    if not cells then build() end
    local w, h = GetPhysicalScreenSize()
    local l, b, fw, fh = frame:GetRect()
    return string.format("screen %dx%d, shown=%s visible=%s, rect=%s,%s %sx%s, scale=%.3f",
        w, h, tostring(frame:IsShown()), tostring(frame:IsVisible()),
        tostring(l), tostring(b), tostring(fw), tostring(fh), frame:GetEffectiveScale())
end

-- Capacity in bytes for one frame of the strip.
function ns.StripCapacity()
    if not cells then build() end
    return math.floor((numCells - DATA - 3) * 6 / 8)
end

function ns.StripWrite(payload)
    if not cells then build() end
    if #payload > ns.StripCapacity() then payload = payload:sub(1, ns.StripCapacity()) end
    if payload == shown then return end
    shown = payload
    seq = (seq + 1) % 64
    setCell(68, seq)
    setCell(69, VERSION)
    setCell(70, bit.rshift(#payload, 6))
    setCell(71, bit.band(#payload, 63))
    local i, acc, nbits = DATA, 0, 0
    for p = 1, #payload do
        acc = bit.bor(bit.lshift(acc, 8), payload:byte(p)); nbits = nbits + 8
        while nbits >= 6 do
            nbits = nbits - 6
            setCell(i, bit.band(bit.rshift(acc, nbits), 63)); i = i + 1
        end
        acc = bit.band(acc, bit.lshift(1, nbits) - 1)
    end
    if nbits > 0 then setCell(i, bit.band(bit.lshift(acc, 6 - nbits), 63)); i = i + 1 end
    local crc = crc16(payload)
    setCell(i, bit.rshift(crc, 12)); setCell(i + 1, bit.band(bit.rshift(crc, 6), 63)); setCell(i + 2, bit.band(crc, 63))
    i = i + 3
    for j = i, numCells - 1 do setCell(j, 0) end
end

function ns.StripMove(offset)
    ns.StripOffset = offset
    if frame then
        frame:ClearAllPoints()
        frame:SetPoint("BOTTOMRIGHT", UIParent, "BOTTOMRIGHT", 0, offset)
    end
end

function ns.StripShow(show)
    if not cells then build() end
    frame:SetShown(show)
end
