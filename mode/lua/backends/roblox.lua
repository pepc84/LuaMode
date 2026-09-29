--[[
  Processing for Lua — Roblox/Luau Backend
  Maps Processing draw calls to Roblox's EditableImage API.

  Setup in a LocalScript:
    local P       = require(game.ReplicatedStorage.Processing)
    local Backend = require(game.ReplicatedStorage.ProcessingBackendRoblox)
    P.setBackend(Backend)
    P.inject(_ENV)

    function setup()
      size(600, 400)
      background(30)
    end

    function draw()
      background(30, 30, 40)
      fill(255, 80, 0)
      ellipse(mouseX, mouseY, 60, 60)
    end

    P.run()

  Note: EditableImage requires the API service to be enabled in
  game settings (beta feature as of 2024). All coordinates are
  screen pixels relative to the ScreenGui anchor.
]]

local RunService      = game:GetService("RunService")
local UserInputService = game:GetService("UserInputService")
local Players         = game:GetService("Players")

local Backend = {}

-- ============================================================
-- Internal state
-- ============================================================

local _canvas    = nil   -- EditableImage
local _label     = nil   -- ImageLabel displaying the canvas
local _gui       = nil   -- ScreenGui
local _W, _H     = 100, 100
local _startTime = 0
local _pState    = nil   -- reference to Processing._state
local _P         = nil   -- reference to the Processing module

-- Convert a Processing {r,g,b,a} color to Roblox Color3 + alpha
local function toRoblox(c)
  return Color3.new(c.r, c.g, c.b), c.a
end

-- ============================================================
-- Canvas creation
-- ============================================================

function Backend.init(pState)
  _pState    = pState
  _startTime = tick()
end

function Backend.createCanvas(w, h)
  _W, _H = w, h

  -- Clean up any previous canvas
  if _gui then _gui:Destroy() end

  -- Create ScreenGui
  local player   = Players.LocalPlayer
  local playerGui = player:WaitForChild("PlayerGui")

  _gui              = Instance.new("ScreenGui")
  _gui.Name         = "ProcessingCanvas"
  _gui.ResetOnSpawn = false
  _gui.ZIndexBehavior = Enum.ZIndexBehavior.Sibling
  _gui.Parent       = playerGui

  -- ImageLabel to display the EditableImage
  _label             = Instance.new("ImageLabel")
  _label.Name        = "Canvas"
  _label.Size        = UDim2.new(0, w, 0, h)
  _label.Position    = UDim2.new(0, 0, 0, 0)
  _label.BackgroundColor3 = Color3.new(0, 0, 0)
  _label.BorderSizePixel  = 0
  _label.ScaleType   = Enum.ScaleType.Stretch
  _label.Parent      = _gui

  -- Create EditableImage attached to the label
  _canvas = Instance.new("EditableImage")
  _canvas.Size   = Vector2.new(w, h)
  _canvas.Parent = _label

  -- Clear to black
  _canvas:DrawRectangle(Vector2.zero, Vector2.new(w, h), Color3.new(0,0,0), 0, Enum.ImageCombineType.Overwrite)
end

-- ============================================================
-- Drawing primitives
-- ============================================================

function Backend.background(c)
  if not _canvas then return end
  local col, _ = toRoblox(c)
  _canvas:DrawRectangle(
    Vector2.zero,
    Vector2.new(_W, _H),
    col,
    1 - c.a,              -- Roblox transparency is inverted (0 = opaque)
    Enum.ImageCombineType.Overwrite
  )
end

-- Filled + stroked helpers
local function rbxAlpha(a)
  return 1 - a  -- Roblox: 0 = fully opaque, 1 = fully transparent
end

-- Draw a single pixel-width line (Bresenham) for stroke outlines
-- EditableImage:DrawLine draws a 1px line; we layer for strokeWeight
local function drawLine(x1, y1, x2, y2, col, alpha, weight)
  if not _canvas then return end
  weight = weight or 1
  local halfW = (weight - 1) / 2
  if weight <= 1 then
    _canvas:DrawLine(
      Vector2.new(math.floor(x1 + 0.5), math.floor(y1 + 0.5)),
      Vector2.new(math.floor(x2 + 0.5), math.floor(y2 + 0.5)),
      col,
      rbxAlpha(alpha),
      Enum.ImageCombineType.AlphaBlend
    )
  else
    -- Offset perpendicular copies for thick lines
    local dx = x2 - x1
    local dy = y2 - y1
    local len = math.sqrt(dx*dx + dy*dy)
    if len == 0 then return end
    local nx = -dy / len
    local ny =  dx / len
    local steps = math.ceil(weight)
    for i = 0, steps - 1 do
      local t  = -halfW + i * (weight / (steps - 1 + 0.001))
      local ox = nx * t
      local oy = ny * t
      _canvas:DrawLine(
        Vector2.new(math.floor(x1 + ox + 0.5), math.floor(y1 + oy + 0.5)),
        Vector2.new(math.floor(x2 + ox + 0.5), math.floor(y2 + oy + 0.5)),
        col,
        rbxAlpha(alpha),
        Enum.ImageCombineType.AlphaBlend
      )
    end
  end
end

function Backend.point(x, y, ds)
  if not ds.hasStroke then return end
  local col, a = toRoblox(ds.strokeColor)
  local w = ds.strokeWeight
  _canvas:DrawRectangle(
    Vector2.new(math.floor(x - w*0.5), math.floor(y - w*0.5)),
    Vector2.new(math.ceil(w), math.ceil(w)),
    col, rbxAlpha(a),
    Enum.ImageCombineType.AlphaBlend
  )
end

function Backend.line(x1, y1, x2, y2, ds)
  if not ds.hasStroke then return end
  local col, a = toRoblox(ds.strokeColor)
  drawLine(x1, y1, x2, y2, col, a, ds.strokeWeight)
end

-- Fill a polygon (triangle fan) using rasterizer
-- We use a scanline fill for convex polygons
local function scanlineFill(verts, col, alpha)
  if #verts < 3 then return end
  local minY, maxY = math.huge, -math.huge
  for _, v in ipairs(verts) do
    if v.y < minY then minY = v.y end
    if v.y > maxY then maxY = v.y end
  end
  minY = math.max(math.floor(minY), 0)
  maxY = math.min(math.ceil(maxY), _H - 1)

  local n = #verts
  for y = minY, maxY do
    local intersections = {}
    for i = 1, n do
      local j  = i % n + 1
      local ay = verts[i].y
      local by = verts[j].y
      local ax = verts[i].x
      local bx = verts[j].x
      if (ay <= y and by > y) or (by <= y and ay > y) then
        local t = (y - ay) / (by - ay)
        intersections[#intersections + 1] = ax + t * (bx - ax)
      end
    end
    table.sort(intersections)
    for k = 1, #intersections - 1, 2 do
      local lx = math.max(math.floor(intersections[k]), 0)
      local rx = math.min(math.ceil(intersections[k+1]), _W - 1)
      if rx >= lx then
        _canvas:DrawRectangle(
          Vector2.new(lx, y),
          Vector2.new(rx - lx + 1, 1),
          col, rbxAlpha(alpha),
          Enum.ImageCombineType.AlphaBlend
        )
      end
    end
  end
end

-- Stroke a polygon outline
local function strokePoly(verts, col, alpha, weight, closed)
  local n = #verts
  for i = 1, n - 1 do
    drawLine(verts[i].x, verts[i].y, verts[i+1].x, verts[i+1].y, col, alpha, weight)
  end
  if closed and n > 2 then
    drawLine(verts[n].x, verts[n].y, verts[1].x, verts[1].y, col, alpha, weight)
  end
end

function Backend.rect(x1, y1, x2, y2, x3, y3, x4, y4, w, h, cornerR, ds)
  -- For axis-aligned rects (no rotation) use EditableImage's native DrawRectangle
  -- Check if it's axis-aligned
  local isAA = (math.abs(y2 - y1) < 0.5 and math.abs(x4 - x1) < 0.5)

  if isAA and cornerR == 0 then
    local lx = math.floor(math.min(x1, x2, x3, x4) + 0.5)
    local ty = math.floor(math.min(y1, y2, y3, y4) + 0.5)
    local rw = math.floor(math.abs(w) + 0.5)
    local rh = math.floor(math.abs(h) + 0.5)

    if ds.hasFill then
      local col, a = toRoblox(ds.fillColor)
      _canvas:DrawRectangle(Vector2.new(lx, ty), Vector2.new(rw, rh), col, rbxAlpha(a), Enum.ImageCombineType.AlphaBlend)
    end
    if ds.hasStroke then
      local col, a = toRoblox(ds.strokeColor)
      local sw = ds.strokeWeight
      -- Draw 4 edges
      drawLine(lx, ty,       lx+rw, ty,       col, a, sw)
      drawLine(lx+rw, ty,    lx+rw, ty+rh,    col, a, sw)
      drawLine(lx+rw, ty+rh, lx,    ty+rh,    col, a, sw)
      drawLine(lx, ty+rh,    lx,    ty,        col, a, sw)
    end
  else
    -- Rotated rect: rasterize as polygon
    local verts = {
      {x=x1,y=y1},{x=x2,y=y2},{x=x3,y=y3},{x=x4,y=y4}
    }
    if ds.hasFill then
      local col, a = toRoblox(ds.fillColor)
      scanlineFill(verts, col, a)
    end
    if ds.hasStroke then
      local col, a = toRoblox(ds.strokeColor)
      strokePoly(verts, col, a, ds.strokeWeight, true)
    end
  end
end

function Backend.ellipse(cx, cy, rx, ry, ds)
  if not _canvas then return end
  -- Use EditableImage:DrawCircle for circles, else rasterize ellipse
  local isCircle = math.abs(rx - ry) < 0.5

  if isCircle then
    if ds.hasFill then
      local col, a = toRoblox(ds.fillColor)
      _canvas:DrawCircle(
        Vector2.new(math.floor(cx + 0.5), math.floor(cy + 0.5)),
        math.floor(rx + 0.5),
        col, rbxAlpha(a),
        Enum.ImageCombineType.AlphaBlend
      )
    end
    if ds.hasStroke then
      -- Draw stroked ring by drawing larger then smaller circle (approximate)
      local col, a = toRoblox(ds.strokeColor)
      local sw = ds.strokeWeight
      -- Rasterize outline points
      local steps = math.max(32, math.floor(rx * 2))
      local verts = {}
      for i = 0, steps - 1 do
        local angle = (i / steps) * math.pi * 2
        verts[#verts + 1] = {
          x = cx + math.cos(angle) * rx,
          y = cy + math.sin(angle) * ry,
        }
      end
      strokePoly(verts, col, a, sw, true)
    end
  else
    -- Rasterize ellipse with scanline
    local steps = math.max(48, math.floor(math.max(rx, ry) * 2))
    local verts = {}
    for i = 0, steps - 1 do
      local angle = (i / steps) * math.pi * 2
      verts[#verts + 1] = {
        x = cx + math.cos(angle) * rx,
        y = cy + math.sin(angle) * ry,
      }
    end
    if ds.hasFill then
      local col, a = toRoblox(ds.fillColor)
      scanlineFill(verts, col, a)
    end
    if ds.hasStroke then
      local col, a = toRoblox(ds.strokeColor)
      strokePoly(verts, col, a, ds.strokeWeight, true)
    end
  end
end

function Backend.arc(cx, cy, rx, ry, startAngle, stopAngle, mode, ds)
  local steps = math.max(32, math.floor(math.max(rx, ry) * 2))
  local span  = stopAngle - startAngle
  local verts = {}

  if mode == "PIE" then
    verts[#verts + 1] = { x = cx, y = cy }
  end

  local numSteps = math.max(4, math.floor(math.abs(span / (math.pi * 2)) * steps))
  for i = 0, numSteps do
    local angle = startAngle + (i / numSteps) * span
    verts[#verts + 1] = {
      x = cx + math.cos(angle) * rx,
      y = cy + math.sin(angle) * ry,
    }
  end

  local closed = (mode == "PIE" or mode == "CHORD")

  if ds.hasFill then
    local col, a = toRoblox(ds.fillColor)
    scanlineFill(verts, col, a)
  end
  if ds.hasStroke then
    local col, a = toRoblox(ds.strokeColor)
    strokePoly(verts, col, a, ds.strokeWeight, closed)
  end
end

function Backend.triangle(ax, ay, bx, by, cx, cy, ds)
  local verts = {{x=ax,y=ay},{x=bx,y=by},{x=cx,y=cy}}
  if ds.hasFill then
    local col, a = toRoblox(ds.fillColor)
    scanlineFill(verts, col, a)
  end
  if ds.hasStroke then
    local col, a = toRoblox(ds.strokeColor)
    strokePoly(verts, col, a, ds.strokeWeight, true)
  end
end

function Backend.quad(ax, ay, bx, by, cx, cy, dx, dy, ds)
  local verts = {{x=ax,y=ay},{x=bx,y=by},{x=cx,y=cy},{x=dx,y=dy}}
  if ds.hasFill then
    local col, a = toRoblox(ds.fillColor)
    scanlineFill(verts, col, a)
  end
  if ds.hasStroke then
    local col, a = toRoblox(ds.strokeColor)
    strokePoly(verts, col, a, ds.strokeWeight, true)
  end
end

function Backend.polyline(pts, ds)
  if ds.hasStroke then
    local col, a = toRoblox(ds.strokeColor)
    strokePoly(pts, col, a, ds.strokeWeight, false)
  end
end

function Backend.shape(verts, kind, closed, ds)
  -- Default polygon / shape rendering
  if kind == nil then
    -- Filled polygon
    if ds.hasFill then
      local col, a = toRoblox(ds.fillColor)
      scanlineFill(verts, col, a)
    end
    if ds.hasStroke then
      local col, a = toRoblox(ds.strokeColor)
      strokePoly(verts, col, a, ds.strokeWeight, closed)
    end
  elseif kind == "LINES" then
    if ds.hasStroke then
      local col, a = toRoblox(ds.strokeColor)
      for i = 1, #verts - 1, 2 do
        drawLine(verts[i].x, verts[i].y, verts[i+1].x, verts[i+1].y, col, a, ds.strokeWeight)
      end
    end
  elseif kind == "POINTS" then
    for _, v in ipairs(verts) do
      Backend.point(v.x, v.y, ds)
    end
  elseif kind == "TRIANGLES" then
    for i = 1, #verts - 2, 3 do
      Backend.triangle(
        verts[i].x, verts[i].y,
        verts[i+1].x, verts[i+1].y,
        verts[i+2].x, verts[i+2].y,
        ds
      )
    end
  elseif kind == "TRIANGLE_FAN" then
    local center = verts[1]
    for i = 2, #verts - 1 do
      Backend.triangle(center.x, center.y, verts[i].x, verts[i].y, verts[i+1].x, verts[i+1].y, ds)
    end
  elseif kind == "TRIANGLE_STRIP" then
    for i = 1, #verts - 2 do
      if i % 2 == 1 then
        Backend.triangle(verts[i].x,verts[i].y, verts[i+1].x,verts[i+1].y, verts[i+2].x,verts[i+2].y, ds)
      else
        Backend.triangle(verts[i+1].x,verts[i+1].y, verts[i].x,verts[i].y, verts[i+2].x,verts[i+2].y, ds)
      end
    end
  elseif kind == "QUADS" then
    for i = 1, #verts - 3, 4 do
      Backend.quad(
        verts[i].x,verts[i].y, verts[i+1].x,verts[i+1].y,
        verts[i+2].x,verts[i+2].y, verts[i+3].x,verts[i+3].y,
        ds
      )
    end
  end
end

-- ============================================================
-- Image support
-- ============================================================

function Backend.loadImage(path)
  -- In Roblox, images are referenced by asset ID, not file path.
  -- path can be an asset ID string like "rbxassetid://1234567890"
  local img = {
    assetId = path,
    width   = 0,
    height  = 0,
  }
  return img
end

function Backend.image(img, x, y, w, h, ds)
  -- Roblox doesn't support drawing arbitrary images into EditableImage
  -- easily without AssetService:CreateEditableImageAsync.
  -- We create an ImageLabel overlay as a practical solution.
  if not _gui then return end
  local lbl = Instance.new("ImageLabel")
  lbl.Image  = img.assetId or ""
  lbl.Size   = UDim2.new(0, w or img.width, 0, h or img.height)
  lbl.Position = UDim2.new(0, x, 0, y)
  lbl.BackgroundTransparency = 1
  lbl.Parent = _gui
  -- Note: this is fire-and-forget; for a proper implementation
  -- you'd track and remove these overlays each frame.
end

-- ============================================================
-- Text
-- ============================================================

function Backend.text(str, x, y, w, h, ds)
  -- Render text as a TextLabel overlay on the ScreenGui
  if not _gui then return end
  local lbl = Instance.new("TextLabel")
  lbl.Text  = str
  lbl.TextSize = ds.textSz or 12
  lbl.Font  = Enum.Font.Code
  lbl.TextColor3 = Color3.new(ds.fillColor.r, ds.fillColor.g, ds.fillColor.b)
  lbl.TextTransparency = 1 - (ds.fillColor.a or 1)
  lbl.BackgroundTransparency = 1
  lbl.Position = UDim2.new(0, x, 0, y)
  lbl.Size     = UDim2.new(0, w or 200, 0, h or (ds.textSz + 4))
  lbl.TextXAlignment = Enum.TextXAlignment.Left
  lbl.TextYAlignment = Enum.TextYAlignment.Top
  lbl.Parent = _gui
  -- Labels rendered by text() are ephemeral: clear them each frame
  -- via the _textLabels table below
  table.insert(_textLabels, lbl)
end

local _textLabels = {}

local function clearTextLabels()
  for _, lbl in ipairs(_textLabels) do
    lbl:Destroy()
  end
  _textLabels = {}
end

-- ============================================================
-- Timing
-- ============================================================

function Backend.millis()
  return (tick() - _startTime) * 1000
end

-- ============================================================
-- Input
-- ============================================================

local function setupInput(P)
  -- Mouse position (relative to the canvas label)
  RunService.RenderStepped:Connect(function()
    local mousePos = UserInputService:GetMouseLocation()
    if _label then
      local absPos  = _label.AbsolutePosition
      local mx = mousePos.X - absPos.X
      local my = mousePos.Y - absPos.Y
      local pressed = UserInputService:IsMouseButtonPressed(Enum.UserInputType.MouseButton1)
      local btn = nil
      if UserInputService:IsMouseButtonPressed(Enum.UserInputType.MouseButton1) then btn = "LEFT"
      elseif UserInputService:IsMouseButtonPressed(Enum.UserInputType.MouseButton2) then btn = "RIGHT"
      elseif UserInputService:IsMouseButtonPressed(Enum.UserInputType.MouseButton3) then btn = "CENTER"
      end
      P._setMouse(mx, my, pressed, btn)
    end
  end)

  -- Key events
  UserInputService.InputBegan:Connect(function(input, gameProcessed)
    if input.UserInputType == Enum.UserInputType.Keyboard then
      local keyName = input.KeyCode.Name
      P._setKey(true, keyName, input.KeyCode.Value)
      -- Call sketch's keyPressed() if defined
      local kp = rawget(_G or {}, "keyPressed")
      if type(kp) == "function" then kp() end
    end
    -- Mouse buttons
    if input.UserInputType == Enum.UserInputType.MouseButton1
    or input.UserInputType == Enum.UserInputType.MouseButton2
    or input.UserInputType == Enum.UserInputType.MouseButton3 then
      local mp = rawget(_G or {}, "mousePressed")
      if type(mp) == "function" then mp() end
    end
  end)

  UserInputService.InputEnded:Connect(function(input)
    if input.UserInputType == Enum.UserInputType.Keyboard then
      P._setKey(false, "", 0)
      local kr = rawget(_G or {}, "keyReleased")
      if type(kr) == "function" then kr() end
    end
    if input.UserInputType == Enum.UserInputType.MouseButton1
    or input.UserInputType == Enum.UserInputType.MouseButton2
    or input.UserInputType == Enum.UserInputType.MouseButton3 then
      local mr = rawget(_G or {}, "mouseReleased")
      if type(mr) == "function" then mr() end
    end
  end)

  UserInputService.InputChanged:Connect(function(input)
    if input.UserInputType == Enum.UserInputType.MouseMovement then
      local mm = rawget(_G or {}, "mouseMoved")
      if type(mm) == "function" then mm() end
    end
  end)
end

-- ============================================================
-- Main run loop
-- ============================================================

function Backend.run(env, pState, P)
  _P = P

  if not _canvas then
    -- Default canvas if size() was never called in setup()
    Backend.createCanvas(pState.width, pState.height)
  end

  setupInput(P)

  -- Connect to RenderStepped for the draw loop
  RunService.RenderStepped:Connect(function(dt)
    -- Clear text overlays from the previous frame
    clearTextLabels()

    -- Run P's frame logic
    P._frame(env)
  end)
end

return Backend
