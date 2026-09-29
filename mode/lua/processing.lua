--[[
  Processing for Lua
  A faithful implementation of the Processing API in Lua/Luau.

  Usage (Roblox):
    local P = require(game.ReplicatedStorage.Processing)
    P.inject(_ENV)          -- puts all Processing globals into your script scope
    local Backend = require(game.ReplicatedStorage.ProcessingRoblox)
    P.setBackend(Backend)

    function setup()
      size(400, 400)
      background(30)
    end

    function draw()
      fill(255, 80, 0)
      ellipse(mouseX, mouseY, 40, 40)
    end

    P.run()

  Usage (LOVE2D / CLI):
    local P = require("processing")
    P.inject(_ENV)
    local Backend = require("backends.love2d")
    P.setBackend(Backend)
    -- ... same sketch code
    P.run()
]]

local P = {}

-- ============================================================
-- Constants
-- ============================================================

P.PI          = math.pi
P.TWO_PI      = math.pi * 2
P.TAU         = math.pi * 2
P.HALF_PI     = math.pi / 2
P.QUARTER_PI  = math.pi / 4
P.E           = 2.718281828459045

-- Shape / mode constants
P.CORNER        = "CORNER"
P.CORNERS       = "CORNERS"
P.CENTER        = "CENTER"
P.RADIUS        = "RADIUS"

P.LEFT          = "LEFT"
P.RIGHT         = "RIGHT"
P.TOP           = "TOP"
P.BOTTOM        = "BOTTOM"
P.BASELINE      = "BASELINE"
P.MIDDLE        = "MIDDLE"

P.RGB           = "RGB"
P.HSB           = "HSB"

P.CLOSE         = "CLOSE"
P.OPEN          = "OPEN"

-- beginShape modes
P.POINTS        = "POINTS"
P.LINES         = "LINES"
P.TRIANGLES     = "TRIANGLES"
P.TRIANGLE_FAN  = "TRIANGLE_FAN"
P.TRIANGLE_STRIP = "TRIANGLE_STRIP"
P.QUADS         = "QUADS"
P.QUAD_STRIP    = "QUAD_STRIP"

-- Mouse button constants
P.MOUSE_LEFT    = "LEFT"
P.MOUSE_RIGHT   = "RIGHT"
P.MOUSE_CENTER  = "CENTER"

-- Key codes (CODED keys)
P.CODED       = 65536
P.UP          = 38 + 65536
P.DOWN        = 40 + 65536
P.LEFT_ARROW  = 37 + 65536
P.RIGHT_ARROW = 39 + 65536
P.ALT         = 18 + 65536
P.CONTROL     = 17 + 65536
P.SHIFT       = 16 + 65536
P.BACKSPACE   = 8
P.TAB         = 9
P.ENTER       = 10
P.RETURN      = 13
P.ESC         = 27
P.DELETE      = 127

-- ============================================================
-- Internal state
-- ============================================================

-- A draw state holds all style + transform info at one stack level
local function newDrawState()
  return {
    -- Fill / stroke
    hasFill       = true,
    fillColor     = { r = 1, g = 1, b = 1, a = 1 },
    hasStroke     = true,
    strokeColor   = { r = 0, g = 0, b = 0, a = 1 },
    strokeWeight  = 1,
    -- Affine 2D transform: {a, b, c, d, tx, ty}
    --   [a  b  tx]
    --   [c  d  ty]   *  [x, y, 1]^T
    --   [0  0   1]
    transform     = { 1, 0, 0, 1, 0, 0 },
    -- Mode flags
    rectMode      = "CORNER",
    ellipseMode   = "CENTER",
    imageMode     = "CORNER",
    colorMode     = "RGB",
    colorRange    = { 255, 255, 255, 255 },
    -- Text
    textSz        = 12,
    textAlignH    = "LEFT",
    textAlignV    = "BASELINE",
    -- Tint for images
    hasTint       = false,
    tintColor     = { r = 1, g = 1, b = 1, a = 1 },
  }
end

local _state = {
  -- Canvas dimensions
  width   = 100,
  height  = 100,
  -- Draw state + stack (for push/pop)
  ds      = newDrawState(),
  stack   = {},
  -- Style-only stack (for pushStyle / popStyle)
  styleStack = {},
  -- Timing
  frameCount      = 0,
  startTime       = 0,
  lastFrameTime   = 0,
  targetFPS       = 60,
  isLooping       = true,
  -- Input
  mouseX          = 0,
  mouseY          = 0,
  pmouseX         = 0,
  pmouseY         = 0,
  mouseIsPressed  = false,
  mouseButton     = nil,
  keyIsPressed    = false,
  key             = "",
  keyCode         = 0,
  -- beginShape buffer
  inShape         = false,
  shapeKind       = nil,
  shapeVerts      = {},
  -- Perlin noise table (lazy-init)
  _perm           = nil,
}

-- ============================================================
-- Backend
-- ============================================================

local _backend = nil

function P.setBackend(b)
  _backend = b
  if b.init then b.init(_state) end
end

-- ============================================================
-- Helpers
-- ============================================================

local function clamp01(v)
  if v < 0 then return 0 elseif v > 1 then return 1 else return v end
end

-- HSV -> RGB, all inputs/outputs in [0,1]
local function hsvToRgb(h, s, v)
  if s == 0 then return v, v, v end
  local i = math.floor(h * 6) % 6
  local f = h * 6 - math.floor(h * 6)
  local p = v * (1 - s)
  local q = v * (1 - f * s)
  local t = v * (1 - (1 - f) * s)
  if i == 0 then return v, t, p
  elseif i == 1 then return q, v, p
  elseif i == 2 then return p, v, t
  elseif i == 3 then return p, q, v
  elseif i == 4 then return t, p, v
  else               return v, p, q end
end

-- Parse Processing-style color arguments into {r,g,b,a} in [0,1]
local function parseColor(a1, a2, a3, a4)
  local ds = _state.ds
  local cr = ds.colorRange[1]
  local cg = ds.colorRange[2]
  local cb = ds.colorRange[3]
  local ca = ds.colorRange[4]

  -- Already a color table
  if type(a1) == "table" then
    return { r = a1.r, g = a1.g, b = a1.b, a = a1.a ~= nil and a1.a or 1 }
  end

  local r, g, b, a

  if a3 ~= nil then
    -- r, g, b [, a]
    if ds.colorMode == "HSB" then
      r, g, b = hsvToRgb(a1 / cr, a2 / cg, a3 / cb)
    else
      r = a1 / cr
      g = a2 / cg
      b = a3 / cb
    end
    a = a4 ~= nil and (a4 / ca) or 1
  elseif a2 ~= nil then
    -- gray, alpha
    local v = a1 / cr
    r, g, b = v, v, v
    a = a2 / ca
  else
    -- gray only
    local v = a1 / cr
    r, g, b, a = v, v, v, 1
  end

  return { r = clamp01(r), g = clamp01(g), b = clamp01(b), a = clamp01(a) }
end

-- Apply the current transform to a point
local function xfPoint(x, y)
  local m = _state.ds.transform
  -- m = {a, b, c, d, tx, ty}
  return m[1]*x + m[2]*y + m[5],
         m[3]*x + m[4]*y + m[6]
end

-- Multiply two 2D affine transforms (both {a,b,c,d,tx,ty})
local function matMul(m1, m2)
  return {
    m1[1]*m2[1] + m1[2]*m2[3],
    m1[1]*m2[2] + m1[2]*m2[4],
    m1[3]*m2[1] + m1[4]*m2[3],
    m1[3]*m2[2] + m1[4]*m2[4],
    m1[1]*m2[5] + m1[2]*m2[6] + m1[5],
    m1[3]*m2[5] + m1[4]*m2[6] + m1[6],
  }
end

-- Deep-copy a draw state
local function copyDs(ds)
  local c = {}
  for k, v in pairs(ds) do
    if type(v) == "table" then
      local t = {}
      for i, u in pairs(v) do t[i] = u end
      c[k] = t
    else
      c[k] = v
    end
  end
  return c
end

-- ============================================================
-- Environment / canvas
-- ============================================================

function P.size(w, h)
  _state.width  = w
  _state.height = h
  P.width       = w
  P.height      = h
  if _backend and _backend.createCanvas then
    _backend.createCanvas(w, h)
  end
end

P.createCanvas = P.size

-- Exported globals (will be updated each frame by P.run)
P.width         = 100
P.height        = 100
P.frameCount    = 0
P.mouseX        = 0
P.mouseY        = 0
P.pmouseX       = 0
P.pmouseY       = 0
P.mouseIsPressed = false
P.mouseButton   = nil
P.keyIsPressed  = false
P.key           = ""
P.keyCode       = 0

-- ============================================================
-- Color API
-- ============================================================

function P.colorMode(mode, max1, max2, max3, maxA)
  _state.ds.colorMode  = mode
  local m = max1 or 255
  _state.ds.colorRange = { m, max2 or m, max3 or m, maxA or m }
end

function P.color(a1, a2, a3, a4)
  return parseColor(a1, a2, a3, a4)
end

function P.red(c)
  return (type(c) == "table" and c.r or 0) * _state.ds.colorRange[1]
end
function P.green(c)
  return (type(c) == "table" and c.g or 0) * _state.ds.colorRange[2]
end
function P.blue(c)
  return (type(c) == "table" and c.b or 0) * _state.ds.colorRange[3]
end
function P.alpha(c)
  local a = type(c) == "table" and c.a or 1
  return a * _state.ds.colorRange[4]
end

function P.lerpColor(c1, c2, t)
  return {
    r = c1.r + (c2.r - c1.r) * t,
    g = c1.g + (c2.g - c1.g) * t,
    b = c1.b + (c2.b - c1.b) * t,
    a = c1.a + (c2.a - c1.a) * t,
  }
end

-- ============================================================
-- Fill / stroke
-- ============================================================

function P.background(a1, a2, a3, a4)
  local c = parseColor(a1, a2, a3, a4)
  if _backend and _backend.background then
    _backend.background(c)
  end
end

function P.fill(a1, a2, a3, a4)
  _state.ds.fillColor = parseColor(a1, a2, a3, a4)
  _state.ds.hasFill   = true
end

function P.noFill()
  _state.ds.hasFill = false
end

function P.stroke(a1, a2, a3, a4)
  _state.ds.strokeColor = parseColor(a1, a2, a3, a4)
  _state.ds.hasStroke   = true
end

function P.noStroke()
  _state.ds.hasStroke = false
end

function P.strokeWeight(w)
  _state.ds.strokeWeight = w
end

function P.tint(a1, a2, a3, a4)
  _state.ds.tintColor = parseColor(a1, a2, a3, a4)
  _state.ds.hasTint   = true
end

function P.noTint()
  _state.ds.hasTint = false
end

-- ============================================================
-- Style push / pop
-- ============================================================

-- push() saves both transform AND style (Processing 3.5+)
function P.push()
  table.insert(_state.stack, copyDs(_state.ds))
end

function P.pop()
  if #_state.stack == 0 then
    error("pop() called without matching push()")
  end
  _state.ds = table.remove(_state.stack)
end

-- pushMatrix / popMatrix save transform only
function P.pushMatrix()
  table.insert(_state.stack, copyDs(_state.ds))
end

function P.popMatrix()
  if #_state.stack == 0 then
    error("popMatrix() called without matching pushMatrix()")
  end
  local saved = table.remove(_state.stack)
  -- Only restore transform
  _state.ds.transform = saved.transform
end

-- pushStyle / popStyle save style only (not transform)
function P.pushStyle()
  table.insert(_state.styleStack, copyDs(_state.ds))
end

function P.popStyle()
  if #_state.styleStack == 0 then
    error("popStyle() called without matching pushStyle()")
  end
  local saved = table.remove(_state.styleStack)
  local xf    = _state.ds.transform  -- preserve current transform
  _state.ds   = saved
  _state.ds.transform = xf
end

-- ============================================================
-- Transform
-- ============================================================

function P.translate(x, y)
  local t = { 1, 0, 0, 1, x, y }
  _state.ds.transform = matMul(_state.ds.transform, t)
end

function P.rotate(angle)
  local c = math.cos(angle)
  local s = math.sin(angle)
  local t = { c, -s, s, c, 0, 0 }
  _state.ds.transform = matMul(_state.ds.transform, t)
end

function P.scale(sx, sy)
  sy = sy or sx
  local t = { sx, 0, 0, sy, 0, 0 }
  _state.ds.transform = matMul(_state.ds.transform, t)
end

function P.shearX(angle)
  local t = { 1, math.tan(angle), 0, 1, 0, 0 }
  _state.ds.transform = matMul(_state.ds.transform, t)
end

function P.shearY(angle)
  local t = { 1, 0, math.tan(angle), 1, 0, 0 }
  _state.ds.transform = matMul(_state.ds.transform, t)
end

function P.resetMatrix()
  _state.ds.transform = { 1, 0, 0, 1, 0, 0 }
end

-- ============================================================
-- Mode setters
-- ============================================================

function P.rectMode(mode)    _state.ds.rectMode    = mode end
function P.ellipseMode(mode) _state.ds.ellipseMode = mode end
function P.imageMode(mode)   _state.ds.imageMode   = mode end

-- ============================================================
-- Shape drawing
-- ============================================================

-- Resolve rect corners from mode
local function resolveRect(x, y, w, h)
  local m = _state.ds.rectMode
  if m == "CORNER" then
    return x, y, w, h
  elseif m == "CORNERS" then
    return x, y, w - x, h - y
  elseif m == "CENTER" then
    return x - w * 0.5, y - h * 0.5, w, h
  elseif m == "RADIUS" then
    return x - w, y - h, w * 2, h * 2
  end
  return x, y, w, h
end

-- Resolve ellipse center/radii from mode
local function resolveEllipse(x, y, w, h)
  local m = _state.ds.ellipseMode
  if m == "CENTER" then
    return x, y, w * 0.5, h * 0.5
  elseif m == "RADIUS" then
    return x, y, w, h
  elseif m == "CORNER" then
    return x + w * 0.5, y + h * 0.5, w * 0.5, h * 0.5
  elseif m == "CORNERS" then
    return (x + w) * 0.5, (y + h) * 0.5, math.abs(w - x) * 0.5, math.abs(h - y) * 0.5
  end
  return x, y, w * 0.5, h * 0.5
end

function P.point(x, y)
  local tx, ty = xfPoint(x, y)
  if _backend and _backend.point then
    _backend.point(tx, ty, _state.ds)
  end
end

function P.line(x1, y1, x2, y2)
  local tx1, ty1 = xfPoint(x1, y1)
  local tx2, ty2 = xfPoint(x2, y2)
  if _backend and _backend.line then
    _backend.line(tx1, ty1, tx2, ty2, _state.ds)
  end
end

function P.rect(x, y, w, h, r)
  local rx, ry, rw, rh = resolveRect(x, y, w, h)
  -- Transform all four corners
  local x1, y1 = xfPoint(rx, ry)
  local x2, y2 = xfPoint(rx + rw, ry)
  local x3, y3 = xfPoint(rx + rw, ry + rh)
  local x4, y4 = xfPoint(rx, ry + rh)
  if _backend and _backend.rect then
    _backend.rect(x1, y1, x2, y2, x3, y3, x4, y4, rw, rh, r or 0, _state.ds)
  end
end

function P.square(x, y, s)
  P.rect(x, y, s, s)
end

function P.ellipse(x, y, w, h)
  local cx, cy, rx, ry = resolveEllipse(x, y, w, h)
  local tx, ty = xfPoint(cx, cy)
  -- Scale radii by transform scale (approximate: use avg of x/y scale)
  local m = _state.ds.transform
  local scaleX = math.sqrt(m[1]*m[1] + m[3]*m[3])
  local scaleY = math.sqrt(m[2]*m[2] + m[4]*m[4])
  if _backend and _backend.ellipse then
    _backend.ellipse(tx, ty, rx * scaleX, ry * scaleY, _state.ds)
  end
end

function P.circle(x, y, d)
  P.ellipse(x, y, d, d)
end

function P.arc(x, y, w, h, startAngle, stopAngle, mode)
  local cx, cy, rx, ry = resolveEllipse(x, y, w, h)
  local tx, ty = xfPoint(cx, cy)
  local m = _state.ds.transform
  local scaleX = math.sqrt(m[1]*m[1] + m[3]*m[3])
  local scaleY = math.sqrt(m[2]*m[2] + m[4]*m[4])
  mode = mode or P.OPEN
  if _backend and _backend.arc then
    _backend.arc(tx, ty, rx * scaleX, ry * scaleY, startAngle, stopAngle, mode, _state.ds)
  end
end

function P.triangle(x1, y1, x2, y2, x3, y3)
  local ax, ay = xfPoint(x1, y1)
  local bx, by = xfPoint(x2, y2)
  local cx, cy = xfPoint(x3, y3)
  if _backend and _backend.triangle then
    _backend.triangle(ax, ay, bx, by, cx, cy, _state.ds)
  end
end

function P.quad(x1, y1, x2, y2, x3, y3, x4, y4)
  local ax, ay = xfPoint(x1, y1)
  local bx, by = xfPoint(x2, y2)
  local cx, cy = xfPoint(x3, y3)
  local dx, dy = xfPoint(x4, y4)
  if _backend and _backend.quad then
    _backend.quad(ax, ay, bx, by, cx, cy, dx, dy, _state.ds)
  end
end

function P.bezier(x1, y1, cx1, cy1, cx2, cy2, x2, y2)
  local pts = {}
  local steps = 32
  for i = 0, steps do
    local t = i / steps
    local mt = 1 - t
    local bx = mt*mt*mt*x1 + 3*mt*mt*t*cx1 + 3*mt*t*t*cx2 + t*t*t*x2
    local by = mt*mt*mt*y1 + 3*mt*mt*t*cy1 + 3*mt*t*t*cy2 + t*t*t*y2
    local tx, ty = xfPoint(bx, by)
    pts[#pts+1] = { x = tx, y = ty }
  end
  if _backend and _backend.polyline then
    _backend.polyline(pts, _state.ds)
  end
end

function P.bezierPoint(a, b, c, d, t)
  local mt = 1 - t
  return mt*mt*mt*a + 3*mt*mt*t*b + 3*mt*t*t*c + t*t*t*d
end

function P.bezierTangent(a, b, c, d, t)
  local mt = 1 - t
  return 3*mt*mt*(b-a) + 6*mt*t*(c-b) + 3*t*t*(d-c)
end

function P.curve(x1, y1, x2, y2, x3, y3, x4, y4)
  -- Catmull-Rom spline through x2->x3
  local steps = 32
  local pts = {}
  for i = 0, steps do
    local t = i / steps
    local t2, t3 = t*t, t*t*t
    local bx = 0.5*((-x1+3*x2-3*x3+x4)*t3 + (2*x1-5*x2+4*x3-x4)*t2 + (-x1+x3)*t + 2*x2)
    local by = 0.5*((-y1+3*y2-3*y3+y4)*t3 + (2*y1-5*y2+4*y3-y4)*t2 + (-y1+y3)*t + 2*y2)
    local tx, ty = xfPoint(bx, by)
    pts[#pts+1] = { x = tx, y = ty }
  end
  if _backend and _backend.polyline then
    _backend.polyline(pts, _state.ds)
  end
end

-- ============================================================
-- Vertex / beginShape / endShape
-- ============================================================

function P.beginShape(kind)
  _state.inShape   = true
  _state.shapeKind = kind  -- nil = polygon
  _state.shapeVerts = {}
end

function P.vertex(x, y)
  local tx, ty = xfPoint(x, y)
  table.insert(_state.shapeVerts, { x = tx, y = ty })
end

function P.curveVertex(x, y)
  -- Treated as vertex for now; a proper impl would do Catmull-Rom
  P.vertex(x, y)
end

function P.bezierVertex(cx1, cy1, cx2, cy2, x2, y2)
  local verts = _state.shapeVerts
  if #verts == 0 then return end
  local prev  = verts[#verts]
  local steps = 24
  for i = 1, steps do
    local t  = i / steps
    local mt = 1 - t
    local bx = mt*mt*mt*prev.x + 3*mt*mt*t*cx1 + 3*mt*t*t*cx2 + t*t*t*x2
    local by = mt*mt*mt*prev.y + 3*mt*mt*t*cy1 + 3*mt*t*t*cy2 + t*t*t*y2
    table.insert(verts, { x = bx, y = by })
  end
end

function P.endShape(close)
  if not _state.inShape then return end
  local verts = _state.shapeVerts
  local kind  = _state.shapeKind
  _state.inShape = false

  if #verts == 0 then return end

  if _backend and _backend.shape then
    _backend.shape(verts, kind, close == P.CLOSE, _state.ds)
  end
end

-- ============================================================
-- Image
-- ============================================================

function P.loadImage(path)
  if _backend and _backend.loadImage then
    return _backend.loadImage(path)
  end
  return nil
end

function P.image(img, x, y, w, h)
  if not img then return end
  local m  = _state.ds.imageMode
  local ix, iy = x, y
  local iw = w or (img.width or 0)
  local ih = h or (img.height or 0)
  if m == "CENTER" then
    ix = x - iw * 0.5
    iy = y - ih * 0.5
  elseif m == "CORNERS" then
    iw = (w or ix) - ix
    ih = (h or iy) - iy
  end
  local tx, ty = xfPoint(ix, iy)
  if _backend and _backend.image then
    _backend.image(img, tx, ty, iw, ih, _state.ds)
  end
end

-- ============================================================
-- Typography
-- ============================================================

function P.textSize(sz)
  _state.ds.textSz = sz
  if _backend and _backend.textSize then _backend.textSize(sz) end
end

function P.textAlign(h, v)
  _state.ds.textAlignH = h or P.LEFT
  _state.ds.textAlignV = v or P.BASELINE
end

function P.text(str, x, y, w, h)
  local tx, ty = xfPoint(x, y)
  if _backend and _backend.text then
    _backend.text(tostring(str), tx, ty, w, h, _state.ds)
  end
end

function P.textWidth(str)
  if _backend and _backend.textWidth then
    return _backend.textWidth(tostring(str), _state.ds)
  end
  return #str * _state.ds.textSz * 0.6  -- rough fallback
end

function P.textAscent()
  if _backend and _backend.textAscent then return _backend.textAscent(_state.ds) end
  return _state.ds.textSz
end

function P.textDescent()
  if _backend and _backend.textDescent then return _backend.textDescent(_state.ds) end
  return _state.ds.textSz * 0.2
end

-- ============================================================
-- Math utilities
-- ============================================================

function P.abs(n)      return math.abs(n) end
function P.ceil(n)     return math.ceil(n) end
function P.floor(n)    return math.floor(n) end
function P.round(n)    return math.floor(n + 0.5) end
function P.sqrt(n)     return math.sqrt(n) end
function P.sq(n)       return n * n end
function P.log(n)      return math.log(n) end
function P.exp(n)      return math.exp(n) end
function P.pow(n, e)   return n ^ e end

function P.max(a, b, ...)
  local m = a
  if b ~= nil then m = b > m and b or m end
  for _, v in ipairs({...}) do m = v > m and v or m end
  return m
end

function P.min(a, b, ...)
  local m = a
  if b ~= nil then m = b < m and b or m end
  for _, v in ipairs({...}) do m = v < m and v or m end
  return m
end

function P.constrain(v, lo, hi)
  if v < lo then return lo
  elseif v > hi then return hi
  else return v end
end

function P.map(value, start1, stop1, start2, stop2)
  return start2 + (stop2 - start2) * ((value - start1) / (stop1 - start1))
end

function P.norm(value, start, stop)
  return (value - start) / (stop - start)
end

function P.lerp(start, stop, amt)
  return start + (stop - start) * amt
end

function P.dist(x1, y1, x2, y2)
  local dx = x2 - x1
  local dy = y2 - y1
  return math.sqrt(dx*dx + dy*dy)
end

function P.mag(a, b)
  return math.sqrt(a*a + b*b)
end

-- Trig
function P.sin(a)   return math.sin(a) end
function P.cos(a)   return math.cos(a) end
function P.tan(a)   return math.tan(a) end
function P.asin(v)  return math.asin(v) end
function P.acos(v)  return math.acos(v) end
function P.atan(v)  return math.atan(v) end
function P.atan2(y, x) return math.atan(y, x) end  -- Lua 5.3+ / Luau
function P.degrees(r) return r * (180 / math.pi) end
function P.radians(d) return d * (math.pi / 180) end

-- ============================================================
-- Random
-- ============================================================

local _randSeed = nil

function P.randomSeed(seed)
  _randSeed = seed
  math.randomseed(seed)
end

function P.random(a, b)
  if b ~= nil then
    return a + math.random() * (b - a)
  elseif a ~= nil then
    return math.random() * a
  else
    return math.random()
  end
end

function P.randomGaussian()
  -- Box-Muller transform
  local u1 = math.random()
  local u2 = math.random()
  return math.sqrt(-2 * math.log(u1)) * math.cos(2 * math.pi * u2)
end

-- ============================================================
-- Perlin noise
-- ============================================================

-- Classic improved Perlin noise (1D, 2D, 3D)
local _perm = nil
local _noiseSeed = 0
local _noiseDetail = { octaves = 4, falloff = 0.5 }

local function initPerm(seed)
  local p = {}
  for i = 0, 255 do p[i] = i end
  -- Shuffle with seed
  math.randomseed(seed or 0)
  for i = 255, 1, -1 do
    local j = math.floor(math.random() * (i + 1))
    p[i], p[j] = p[j], p[i]
  end
  _perm = {}
  for i = 0, 511 do
    _perm[i] = p[i % 256]
  end
  if _randSeed then math.randomseed(_randSeed) end  -- restore
end

local function fade(t) return t*t*t*(t*(t*6-15)+10) end
local function noiseGrad(hash, x, y, z)
  local h = hash % 16
  local u = h < 8 and x or y
  local v = h < 4 and y or (h == 12 or h == 14) and x or z
  return ((h % 2 == 0) and u or -u) + ((math.floor(h/2) % 2 == 0) and v or -v)
end

local function rawNoise3(x, y, z)
  if not _perm then initPerm(0) end
  local X = math.floor(x) % 256
  local Y = math.floor(y) % 256
  local Z = math.floor(z) % 256
  x = x - math.floor(x)
  y = y - math.floor(y)
  z = z - math.floor(z)
  local u, v, w = fade(x), fade(y), fade(z)
  local A  = _perm[X] + Y
  local AA = _perm[A] + Z
  local AB = _perm[A+1] + Z
  local B  = _perm[X+1] + Y
  local BA = _perm[B] + Z
  local BB = _perm[B+1] + Z

  local function g(h, dx, dy, dz) return noiseGrad(_perm[h], dx, dy, dz) end
  local function lerp(t, a, b) return a + t*(b-a) end

  return lerp(w,
    lerp(v,
      lerp(u, g(AA, x, y, z),   g(BA, x-1, y, z)),
      lerp(u, g(AB, x, y-1, z), g(BB, x-1, y-1, z))),
    lerp(v,
      lerp(u, g(AA+1, x, y, z-1),   g(BA+1, x-1, y, z-1)),
      lerp(u, g(AB+1, x, y-1, z-1), g(BB+1, x-1, y-1, z-1))))
end

function P.noiseSeed(seed)
  _noiseSeed = seed
  initPerm(seed)
end

function P.noiseDetail(octaves, falloff)
  _noiseDetail.octaves = octaves or 4
  _noiseDetail.falloff = falloff or 0.5
end

function P.noise(x, y, z)
  y = y or 0
  z = z or 0
  local value    = 0
  local scale    = 1
  local amplitude = 0.5
  local max       = 0
  for _ = 1, _noiseDetail.octaves do
    value     = value + rawNoise3(x * scale, y * scale, z * scale) * amplitude
    max       = max + amplitude
    scale     = scale * 2
    amplitude = amplitude * _noiseDetail.falloff
  end
  return (value / max) * 0.5 + 0.5  -- remap to [0, 1]
end

-- ============================================================
-- Time / loop control
-- ============================================================

function P.millis()
  if _backend and _backend.millis then
    return _backend.millis()
  end
  return (os.clock and os.clock() * 1000) or 0
end

function P.second()  return math.floor(P.millis() / 1000) % 60 end
function P.minute()  return math.floor(P.millis() / 60000) % 60 end
function P.hour()    return math.floor(P.millis() / 3600000) % 24 end

function P.frameRate(fps)
  _state.targetFPS = fps
  if _backend and _backend.setFrameRate then _backend.setFrameRate(fps) end
end

function P.loop()    _state.isLooping = true  end
function P.noLoop()  _state.isLooping = false end
function P.redraw()
  -- Force a single draw call
  if _backend and _backend.redraw then _backend.redraw() end
end

function P.exit()
  if _backend and _backend.exit then _backend.exit() end
end

-- ============================================================
-- Cursor
-- ============================================================

function P.cursor(cursorType)
  if _backend and _backend.cursor then _backend.cursor(cursorType) end
end

function P.noCursor()
  if _backend and _backend.noCursor then _backend.noCursor() end
end

-- ============================================================
-- Input helpers (called by backends to update state)
-- ============================================================

-- Backends call these to push events into Processing state
function P._setMouse(mx, my, pressed, button)
  _state.pmouseX         = _state.mouseX
  _state.pmouseY         = _state.mouseY
  _state.mouseX          = mx
  _state.mouseY          = my
  _state.mouseIsPressed  = pressed or false
  _state.mouseButton     = button
  P.mouseX               = mx
  P.mouseY               = my
  P.pmouseX              = _state.pmouseX
  P.pmouseY              = _state.pmouseY
  P.mouseIsPressed       = _state.mouseIsPressed
  P.mouseButton          = button
end

function P._setKey(pressed, k, code)
  _state.keyIsPressed = pressed
  _state.key          = k or ""
  _state.keyCode      = code or 0
  P.keyIsPressed      = pressed
  P.key               = k or ""
  P.keyCode           = code or 0
end

-- ============================================================
-- Main run loop
-- ============================================================

-- Called once per frame from the backend's loop
function P._frame(env)
  -- Update exported globals from state
  P.frameCount = _state.frameCount

  -- Call user's draw()
  if _state.isLooping then
    local drawFn = env and env.draw or rawget(_G or {}, "draw")
    if drawFn then drawFn() end
  end

  _state.frameCount = _state.frameCount + 1
end

--[[
  P.run(env) — start the Processing loop.

  Call this at the end of your sketch. Pass the local environment
  if your sketch functions are local:

    local env = {}
    function env.setup() ... end
    function env.draw() ... end
    P.run(env)

  Or use P.inject(_ENV) and call P.run() with no argument to use
  the global scope (works in Roblox LocalScript top-level).
]]
function P.run(env)
  env = env or _G or {}

  -- Inject globals into env so the sketch can call them unqualified
  P.inject(env)

  if not _backend then
    error("No backend set. Call P.setBackend(backend) before P.run().")
  end

  _state.frameCount = 0
  _state.startTime  = P.millis()

  -- Call user's setup()
  local setupFn = env.setup or rawget(_G or {}, "setup")
  if setupFn then setupFn() end

  -- Hand off to backend's run loop
  _backend.run(env, _state, P)
end

-- ============================================================
-- inject — push all Processing names into an environment table
-- ============================================================

-- List of names that become globals in the sketch scope
local _globalNames = {
  -- Constants
  "PI","TWO_PI","TAU","HALF_PI","QUARTER_PI","E",
  "CORNER","CORNERS","CENTER","RADIUS",
  "LEFT","RIGHT","TOP","BOTTOM","BASELINE","MIDDLE",
  "RGB","HSB","CLOSE","OPEN",
  "POINTS","LINES","TRIANGLES","TRIANGLE_FAN","TRIANGLE_STRIP","QUADS","QUAD_STRIP",
  "CODED","UP","DOWN","LEFT_ARROW","RIGHT_ARROW","ALT","CONTROL","SHIFT",
  "BACKSPACE","TAB","ENTER","RETURN","ESC","DELETE",
  "MOUSE_LEFT","MOUSE_RIGHT","MOUSE_CENTER",
  -- Environment
  "width","height","frameCount",
  "mouseX","mouseY","pmouseX","pmouseY","mouseIsPressed","mouseButton",
  "keyIsPressed","key","keyCode",
  -- Canvas
  "size","createCanvas",
  -- Color
  "colorMode","color","red","green","blue","alpha","lerpColor",
  "background","fill","noFill","stroke","noStroke","strokeWeight","tint","noTint",
  -- Transform
  "push","pop","pushMatrix","popMatrix","pushStyle","popStyle",
  "translate","rotate","scale","shearX","shearY","resetMatrix",
  -- Mode
  "rectMode","ellipseMode","imageMode",
  -- Shapes
  "point","line","rect","square","ellipse","circle","arc",
  "triangle","quad","bezier","bezierPoint","bezierTangent","curve",
  "beginShape","vertex","curveVertex","bezierVertex","endShape",
  -- Image
  "loadImage","image",
  -- Text
  "textSize","textAlign","text","textWidth","textAscent","textDescent",
  -- Math
  "abs","ceil","floor","round","sqrt","sq","log","exp","pow",
  "max","min","constrain","map","norm","lerp","dist","mag",
  "sin","cos","tan","asin","acos","atan","atan2","degrees","radians",
  "random","randomSeed","randomGaussian",
  "noise","noiseSeed","noiseDetail",
  -- Time / loop
  "millis","second","minute","hour",
  "frameRate","loop","noLoop","redraw","exit",
  "cursor","noCursor",
}

function P.inject(env)
  for _, name in ipairs(_globalNames) do
    if P[name] ~= nil then
      env[name] = P[name]
    end
  end
end

-- ============================================================
-- Expose internal state (for backends)
-- ============================================================

P._state   = _state
P._backend = function() return _backend end

return P
