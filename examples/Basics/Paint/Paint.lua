--[[
  Paint — Processing for Lua
  Click and drag to draw. Press 'c' to clear. Press 1-5 to change colour.
  Tests mousePressed, mouseDragged, keyPressed, and the key global.
]]

local colours = {
  {255, 80,  40},   -- 1 red-orange
  {80,  200, 255},  -- 2 sky blue
  {80,  220, 120},  -- 3 green
  {255, 220, 60},   -- 4 yellow
  {200, 80,  255},  -- 5 violet
}
local current = 1
local brushSize = 14

function setup()
  size(640, 480)
  background(20, 20, 28)
  strokeCap(ROUND)
  textSize(13)
end

function draw()
  -- Status bar at the top
  fill(15, 15, 20, 200)
  noStroke()
  rect(0, 0, width, 24)

  local c = colours[current]
  fill(c[1], c[2], c[3])
  text("colour " .. current .. "   brush " .. brushSize ..
       "   press 1-5 to switch   c to clear", 8, 16)
end

function mouseDragged()
  local c = colours[current]
  stroke(c[1], c[2], c[3], 200)
  strokeWeight(brushSize)
  line(pmouseX, pmouseY, mouseX, mouseY)
end

function mousePressed()
  -- Single dot on click
  local c = colours[current]
  fill(c[1], c[2], c[3], 220)
  noStroke()
  ellipse(mouseX, mouseY, brushSize, brushSize)
end

function keyPressed()
  local k = string.byte(key)

  -- Number keys 1-5: select colour
  if k >= 49 and k <= 53 then
    current = k - 48
  end

  -- 'c' or 'C': clear canvas
  if key == 'c' or key == 'C' then
    background(20, 20, 28)
  end

  -- '[' / ']': resize brush
  if key == '[' and brushSize > 2  then brushSize = brushSize - 2 end
  if key == ']' and brushSize < 60 then brushSize = brushSize + 2 end
end
