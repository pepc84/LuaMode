--[[
  LOVE2D Starter -- Processing for Lua

  Run from the IDE: Sketch > Export for LOVE2D, then:
    love MySketch-love2d/

  Run directly from this repo (development):
    love . --sketch examples/Export/LOVE2D\ Starter/LOVE2D\ Starter.lua

  Same sketch source works in the Processing IDE (via luamode-runner),
  LOVE2D, and Roblox with no changes to this file.
]]

-- ── Sketch ──────────────────────────────────────────────────────────────────

local W, H = 800, 500

-- Particles
local NUM_PARTICLES = 80
local particles = {}

-- Ripples triggered by mouse clicks
local ripples = {}

local angle = 0

function setup()
  size(W, H)
  background(10, 10, 20)

  for i = 1, NUM_PARTICLES do
    particles[i] = spawnParticle()
  end
end

function draw()
  -- Dark translucent overlay for motion trails
  fill(10, 10, 20, 40)
  noStroke()
  rect(0, 0, width, height)

  -- Rotating ring in the centre
  push()
  translate(width / 2, height / 2)
  rotate(angle)
  angle = angle + 0.008

  strokeWeight(1.2)
  noFill()
  for i = 0, 7 do
    local a  = (i / 8) * TWO_PI
    local r1 = 50 + sin(angle * 3 + i) * 10
    local r2 = r1 + 40
    local hue = (i / 8 + frameCount * 0.002) % 1
    local r, g, b = hsvToRgb(hue, 0.7, 1.0)
    stroke(r, g, b, 200)
    line(cos(a)*r1, sin(a)*r1, cos(a)*r2, sin(a)*r2)
  end
  pop()

  -- Particles
  for _, p in ipairs(particles) do
    updateParticle(p)
    drawParticle(p)
  end

  -- Ripples
  for i = #ripples, 1, -1 do
    local rip = ripples[i]
    rip.radius = rip.radius + 3
    rip.alpha  = rip.alpha  - 6
    if rip.alpha <= 0 then
      table.remove(ripples, i)
    else
      noFill()
      stroke(rip.r, rip.g, rip.b, rip.alpha)
      strokeWeight(2)
      ellipse(rip.x, rip.y, rip.radius * 2, rip.radius * 2)
    end
  end

  -- Mouse glow
  fill(255, 220, 100, 160)
  noStroke()
  ellipse(mouseX, mouseY, 16, 16)

  -- HUD
  fill(160)
  textSize(13)
  text("Processing for Lua on LOVE2D   |   " .. frameCount .. " frames", 10, 20)
end

function mousePressed()
  ripples[#ripples + 1] = {
    x      = mouseX,
    y      = mouseY,
    radius = 4,
    alpha  = 220,
    r      = math.random(160, 255),
    g      = math.random(100, 220),
    b      = math.random(80, 255),
  }
end

-- ── Helpers ──────────────────────────────────────────────────────────────────

function spawnParticle()
  local hue = math.random()
  local r, g, b = hsvToRgb(hue, 0.6, 1.0)
  return {
    x    = random(W),
    y    = random(H),
    vx   = random(-0.8, 0.8),
    vy   = random(-1.5, -0.3),
    size = random(2, 5),
    r    = r, g = g, b = b,
    life = random(60, 200),
    age  = 0,
  }
end

function updateParticle(p)
  p.x   = p.x + p.vx
  p.y   = p.y + p.vy
  p.age = p.age + 1
  if p.age >= p.life or p.y < -p.size then
    -- Reset rather than remove to avoid table churn
    local fresh = spawnParticle()
    for k, v in pairs(fresh) do p[k] = v end
    p.y = H + p.size
    p.age = 0
  end
end

function drawParticle(p)
  local alpha = 200 * (1 - p.age / p.life)
  noStroke()
  fill(p.r, p.g, p.b, alpha)
  ellipse(p.x, p.y, p.size * 2, p.size * 2)
end

-- Simple HSV -> RGB (all values 0-255 output)
function hsvToRgb(h, s, v)
  local i = math.floor(h * 6)
  local f = h * 6 - i
  local p = v * (1 - s)
  local q = v * (1 - f * s)
  local t = v * (1 - (1 - f) * s)
  local r, g, b
  local m = i % 6
  if m == 0 then r,g,b = v,t,p
  elseif m == 1 then r,g,b = q,v,p
  elseif m == 2 then r,g,b = p,v,t
  elseif m == 3 then r,g,b = p,q,v
  elseif m == 4 then r,g,b = t,p,v
  else               r,g,b = v,p,q
  end
  return r*255, g*255, b*255
end
