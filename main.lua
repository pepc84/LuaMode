--[[
  Processing for Lua — LOVE2D Launcher
  =====================================
  This file lets you run any Processing-for-Lua sketch directly in LOVE2D
  without touching the sketch source.

  Usage (from the LuaMode repo root):

    love . --sketch examples/Basics/Bouncing\ Balls/Bouncing\ Balls.lua

  The --sketch flag accepts any path, absolute or relative to the current
  working directory.  If omitted, the launcher shows usage and quits.

  How it works:
    1. Sets up the package.path so that require("processing") and
       require("backends.love2d") resolve to mode/lua/ in this repo.
    2. Loads processing.lua and wires up the LOVE2D backend.
    3. Injects all Processing globals into the sketch's environment.
    4. Loads and executes the sketch file.
    5. Hands control to the backend's run loop (love.update / love.draw
       callbacks set up by the backend).

  The sketch file is executed in its own environment table (an _ENV proxy
  backed by _G), so its globals don't pollute this launcher's own state.
]]

-- ── Parse --sketch argument ────────────────────────────────────────────────

local sketchPath
do
    local args = arg or {}
    for i, v in ipairs(args) do
        if v == "--sketch" and args[i + 1] then
            sketchPath = args[i + 1]
            break
        end
    end
end

if not sketchPath then
    io.stderr:write(
        "usage: love . --sketch <path/to/sketch.lua>\n" ..
        "  example: love . --sketch examples/Basics/Hello\\ World/Hello\\ World.lua\n"
    )
    love.event.quit(1)
    return
end

-- ── Set up require paths ───────────────────────────────────────────────────
--
-- LOVE2D's package.path already includes the game directory (LuaMode/) at
-- the front.  We add mode/lua/ so that:
--   require("processing")       → mode/lua/processing.lua
--   require("backends.love2d")  → mode/lua/backends/love2d.lua

local sep = package.config:sub(1,1)   -- '/' on Unix, '\\' on Windows
local base = love.filesystem.getSourceBaseDirectory() or "."

-- love.filesystem.getSource() is the game directory (where this main.lua is)
local gameDir = love.filesystem.getSource()
local luaDir  = gameDir .. sep .. "mode" .. sep .. "lua"

-- Prepend mode/lua to both the Lua and C searcher paths
package.path = luaDir .. sep .. "?.lua;"
            .. luaDir .. sep .. "?" .. sep .. "init.lua;"
            .. package.path

-- ── Bootstrap Processing ───────────────────────────────────────────────────

local Processing = require("processing")
local Love2DBack = require("backends.love2d")

Processing.setBackend(Love2DBack)

-- ── Load the sketch ────────────────────────────────────────────────────────
--
-- We load the sketch with loadfile() so Lua error messages include the real
-- file path, matching what luamode-runner does (it sets the chunk name to the
-- sketch path).  Processing globals are injected by P.run() before setup() runs.

local chunk, err = loadfile(sketchPath)
if not chunk then
    io.stderr:write("lua: " .. tostring(err) .. "\n")
    love.event.quit(1)
    return
end

-- Create a sketch environment inheriting from _G so standard Lua libs
-- (math, string, table, io, …) are available to the sketch.
-- Processing globals are injected by P.run() before setup() is called.
local sketchEnv = setmetatable({}, { __index = _G })

-- Bind the chunk to the sketch environment so that top-level assignments
-- (function setup() … end) land in sketchEnv instead of _G.
setfenv(chunk, sketchEnv)
local ok, runErr = pcall(chunk)
if not ok then
    io.stderr:write("lua: " .. tostring(runErr) .. "\n")
    love.event.quit(1)
    return
end

-- ── Hand off to the backend run loop ──────────────────────────────────────
--
-- The LOVE2D backend installs love.update / love.draw / love.keypressed etc.
-- and calls P._frame(env) each draw tick, which calls setup() on the first
-- frame and draw() on subsequent frames.

Processing.run(sketchEnv)
