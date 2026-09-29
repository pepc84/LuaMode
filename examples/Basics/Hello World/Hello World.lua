--[[
  Hello World — Processing for Lua
  A minimal sketch: background, a moving circle, text.

  Run in Roblox:
    Paste into a LocalScript after requiring Processing + backend.

  Run with LOVE2D:
    love . (from the processing-lua directory with a main.lua that
    sets up the backend and requires this file)
]]

function setup()
  size(600, 400)
  textSize(18)
end

function draw()
  background(20, 20, 30)

  -- Pulsing ring
  local r = 60 + sin(frameCount * 0.05) * 20
  noFill()
  stroke(100, 200, 255)
  strokeWeight(2)
  ellipse(width / 2, height / 2, r * 2, r * 2)

  -- Filled circle that follows the mouse
  noStroke()
  fill(255, 80, 40)
  ellipse(mouseX, mouseY, 30, 30)

  -- Label
  fill(200)
  noStroke()
  text("Processing for Lua  |  frame " .. frameCount, 14, 18)
end
