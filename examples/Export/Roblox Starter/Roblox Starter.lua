--[[
  Roblox Starter — Processing for Lua

  Drop this into a LocalScript in Roblox Studio after the boilerplate header
  (or use Sketch > Export for Roblox from the Processing IDE to get the full file).

  Required setup in ReplicatedStorage:
    Processing               (ModuleScript — processing.lua)
    ProcessingBackendRoblox  (ModuleScript — backends/roblox.lua)
]]

-- Boilerplate — stays the same for every Roblox sketch
local P       = require(game.ReplicatedStorage.Processing)
local Backend = require(game.ReplicatedStorage.ProcessingBackendRoblox)
P.setBackend(Backend)
P.inject(_ENV)

-- ── Sketch ──────────────────────────────────────────────────────────────────

local angle = 0
local dots  = {}

function setup()
  size(800, 500)
  background(15, 15, 25)

  for i = 1, 60 do
    dots[i] = {
      x   = random(800),
      y   = random(500),
      r   = random(2, 6),
      spd = random(0.5, 2),
      cr  = random(100, 255),
      cg  = random(80, 200),
      cb  = random(180, 255),
    }
  end
end

function draw()
  -- Semi-transparent overlay for trails
  fill(15, 15, 25, 30)
  noStroke()
  rect(0, 0, width, height)

  -- Spinning star in the centre
  push()
  translate(width / 2, height / 2)
  rotate(angle)
  angle = angle + 0.01

  stroke(80, 160, 255)
  strokeWeight(1.5)
  noFill()
  for i = 0, 5 do
    local a = (i / 6) * TWO_PI
    line(cos(a)*60, sin(a)*60, cos(a + PI/6)*120, sin(a + PI/6)*120)
  end
  pop()

  -- Drifting dots
  for _, d in ipairs(dots) do
    d.y = d.y - d.spd
    if d.y < -d.r then d.y = height + d.r end

    noStroke()
    fill(d.cr, d.cg, d.cb, 200)
    ellipse(d.x, d.y, d.r * 2, d.r * 2)
  end

  -- Mouse cursor highlight
  fill(255, 200, 80, 180)
  noStroke()
  ellipse(mouseX, mouseY, 18, 18)

  -- HUD
  fill(180)
  textSize(14)
  text("Processing for Lua on Roblox   |   " .. frameCount .. " frames", 10, 20)
end

P.run()
