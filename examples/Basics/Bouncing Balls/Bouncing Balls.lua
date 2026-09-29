--[[
  Bouncing Balls — Processing for Lua
  Classic Processing demo, Roblox-ready.
]]

local balls = {}
local NUM = 12

function setup()
  size(640, 480)

  for i = 1, NUM do
    balls[i] = {
      x  = random(width),
      y  = random(height),
      vx = random(-4, 4),
      vy = random(-4, 4),
      r  = random(14, 36),
      hue = random(360),
    }
  end

  colorMode(HSB, 360, 100, 100, 255)
end

function draw()
  -- Fading trail effect
  fill(0, 0, 10, 40)
  noStroke()
  rect(0, 0, width, height)

  for _, b in ipairs(balls) do
    -- Update
    b.x = b.x + b.vx
    b.y = b.y + b.vy

    if b.x - b.r < 0 then
      b.x  = b.r
      b.vx = -b.vx
    end
    if b.x + b.r > width then
      b.x  = width - b.r
      b.vx = -b.vx
    end
    if b.y - b.r < 0 then
      b.y  = b.r
      b.vy = -b.vy
    end
    if b.y + b.r > height then
      b.y  = height - b.r
      b.vy = -b.vy
    end

    -- Draw
    b.hue = (b.hue + 0.5) % 360
    fill(b.hue, 80, 95)
    noStroke()
    ellipse(b.x, b.y, b.r * 2, b.r * 2)

    -- Specular highlight
    fill(360, 0, 100, 120)
    ellipse(b.x - b.r * 0.25, b.y - b.r * 0.3, b.r * 0.5, b.r * 0.4)
  end
end
