--[[
  Noise Terrain — Processing for Lua
  Scrolling Perlin noise landscape. Shows noise(), push/pop, and
  vertex shapes. Works in Roblox and LOVE2D.
]]

local cols, rows = 40, 30
local scl        = 16
local flying     = 0

function setup()
  size(cols * scl, rows * scl)
  noiseDetail(4, 0.5)
  strokeWeight(1)
end

function draw()
  background(10, 10, 20)

  flying = flying - 0.01

  push()
  translate(0, height / 2)

  local yoff = flying
  for y = 0, rows - 1 do
    local xoff = 0
    for x = 0, cols - 1 do
      local h = map(noise(xoff, yoff), 0, 1, -height * 0.5, height * 0.5)

      -- Color based on height
      local r = map(h, -height*0.5, height*0.5, 20, 80)
      local g = map(h, -height*0.5, height*0.5, 60, 200)
      local b = map(h, -height*0.5, height*0.5, 40, 120)

      fill(r, g, b)
      stroke(r * 0.4, g * 0.4, b * 0.4)

      beginShape(QUADS)
        vertex(x * scl,       h)
        vertex((x+1) * scl,   h)
        vertex((x+1) * scl,   height * 0.5)
        vertex(x * scl,       height * 0.5)
      endShape()

      xoff = xoff + 0.1
    end
    yoff = yoff + 0.1
  end

  pop()

  -- Horizon glow
  noStroke()
  for i = 0, 8 do
    local a = map(i, 0, 8, 80, 0)
    fill(60, 120, 200, a)
    rect(0, height/2 - i * 2, width, 4)
  end

  fill(180)
  noStroke()
  textSize(13)
  text("noise terrain   frame " .. frameCount, 8, 16)
end
