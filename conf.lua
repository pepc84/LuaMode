--[[
  LOVE2D configuration for the Processing-for-Lua launcher.

  LOVE2D loads this file before main.lua.  We start with a hidden window so
  there is no flash of a default 800×600 window before the sketch's size()
  call sets the real dimensions.

  The sketch calls size(w, h) during setup(), which calls
  Backend.createCanvas(w, h) → love.window.setMode(w, h, ...) and makes
  the window visible.
]]

function love.conf(t)
    t.window.title   = "Processing for Lua"
    t.window.width   = 640
    t.window.height  = 480
    t.window.visible = false    -- hidden until size() is called in setup()
    t.window.vsync   = 1
    t.window.msaa    = 4
    t.console        = false    -- don't open a console window on Windows
end
