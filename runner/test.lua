local x = 0
function setup()
  size(400, 300)
  println("setup ok")
end

function draw()
  background(30)
  -- RGB fill + stroke
  fill(255, 120, 0)
  stroke(255)
  strokeWeight(2)
  ellipse(80, 80, 80, 80)
  -- HSB color mode
  colorMode(HSB, 360, 100, 100)
  noStroke()
  for i = 0, 5 do
    fill(i * 60, 80, 100)
    rect(160 + i * 35, 40, 30, 30)
  end
  colorMode(RGB, 255)
  -- vertex shape
  fill(80, 180, 255)
  beginShape()
  vertex(40, 200); vertex(120, 160); vertex(150, 260); vertex(60, 280)
  endShape(CLOSE)
  -- bezier
  noFill(); stroke(255, 255, 0)
  bezier(180, 250, 220, 150, 300, 300, 370, 200)
  -- transforms + text alignment
  pushMatrix()
  translate(280, 130)
  rotate(x)
  fill(200, 60, 200); noStroke()
  rect(-20, -20, 40, 40)
  popMatrix()
  fill(255)
  textSize(16)
  textAlign(CENTER)
  text("hello lua", 200, 120)
  x = x + 0.05
  if frameCount == 30 then
    saveFrame("frame.png")
    println("frame 30, noise=" .. string.format("%.3f", noise(0.5, 1.2)) ..
            " random=" .. string.format("%.3f", random(10)) ..
            " gauss=" .. string.format("%.3f", randomGaussian()))
  end
end
