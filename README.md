# Lua Mode for Processing 4

Write Processing sketches in Lua. Run them in the IDE, export to LOVE2D, or paste directly into Roblox — same file, no changes.

```lua
function setup()
  size(640, 480)
end

function draw()
  background(20, 20, 30)
  fill(255, 80, 40)
  ellipse(mouseX, mouseY, 40, 40)
end
```

## How it works

The IDE path uses **luamode-runner**, a small Rust binary that embeds Lua 5.4 via mlua and drives the Processing event loop through the same `App` builder that native Processing sketches use. No LuaJIT FFI, no separate window creation step — the runner *is* a Processing sketch, just driven by Lua. Every Processing function (`rect`, `noise`, `lerpColor`, ...) is registered as a Lua global before your sketch loads.

The LOVE2D and Roblox paths use `mode/lua/processing.lua`, a pure-Lua implementation of the Processing API that delegates to a swappable backend. Same sketch source, different host.

Syntax highlighting and real-time lint feedback are powered by tree-sitter.

## Building

You need:
- Java 11+
- Gradle 8+
- Rust (stable) — optional; only needed to build `luamode-runner`

Set `PROCESSING_HOME` to your Processing 4 installation:

```sh
export PROCESSING_HOME=/path/to/processing-4
./gradlew build
```

To skip the Rust build (Java-only iteration):

```sh
SKIP_RUNNER=1 ./gradlew build
```

The fat JAR lands at `mode/LuaMode.jar`. To build the distribution ZIP:

```sh
./gradlew dist
# → build/distributions/LuaMode-0.1.0.zip
```

### Building luamode-runner by hand

```sh
cd runner
cargo build --release
# binary at runner/target/release/luamode-runner
```

Copy it to `mode/runner/<platform>/luamode-runner` where `<platform>` is one of:
`linux-x86_64`, `linux-aarch64`, `macos-x86_64`, `macos-aarch64`, `windows-x86_64`.

### Building the tree-sitter grammar

The `lib/linux-x86_64/libtree-sitter-lua.so` in this repo was built from
[tree-sitter-grammars/tree-sitter-lua](https://github.com/tree-sitter-grammars/tree-sitter-lua).
For other platforms:

```sh
git clone https://github.com/tree-sitter-grammars/tree-sitter-lua
cd tree-sitter-lua
# Linux / macOS:
cc -shared -fPIC -O2 src/parser.c src/scanner.c -o libtree-sitter-lua.so
# macOS:
cc -shared -fPIC -O2 src/parser.c src/scanner.c -o libtree-sitter-lua.dylib
# Windows (MSVC):
cl /LD src/parser.c src/scanner.c /Fe:tree-sitter-lua.dll
```

Drop the result in `lib/<platform>/`.

## Installing in Processing

1. Unzip `LuaMode-<version>.zip` into your Processing modes folder:
   - macOS: `~/Documents/Processing/modes/`
   - Linux: `~/sketchbook/modes/`
   - Windows: `Documents\Processing\modes\`
2. Restart Processing 4.
3. Switch to Lua Mode from the mode dropdown.

## Project layout

```
LuaMode/
  src/java/processing/mode/lua/
    LuaMode.java          Mode plugin entry point; extracts luamode-runner from JAR
    LuaEditor.java        Editor window; owns token marker and run/stop
    LuaBuild.java         Assembles combined source, runs luamode-runner, maps errors
    LuaRunner.java        Drives LuaBuild on a background thread
    LuaErrorMapper.java   Maps interpreter error lines back to sketch tabs
    LuaLinter.java        Tree-sitter AST lint (syntax errors, setup()/draw() calls)
    LuaExporter.java      Roblox LocalScript export
    LuaInputHandler.java  Auto-pairing for (), [], {}, quotes
    TsLuaTokenMarker.java Async tree-sitter syntax highlighter (debounced, 150ms)
    TsLuaEngine.java      Thread-safe pool of tree-sitter parsers
    LuaProblem.java       Error/warning with tab + line
  runner/
    src/main.rs           luamode-runner: Rust binary, embeds Lua 5.4 via mlua
    Cargo.toml
  mode/lua/
    processing.lua        Pure-Lua Processing API (for LOVE2D + Roblox paths)
    backends/
      love2d.lua
      roblox.lua
  examples/
    Basics/               Hello World, Bouncing Balls, Noise Terrain, Paint
    Export/               Roblox Starter, LOVE2D Starter
  queries/
    highlights.scm        Tree-sitter syntax highlight queries
    tags.scm              Go-to-definition tags
  lib/
    <platform>/           Compiled tree-sitter-lua grammar (.so / .dylib / .dll)
```

## Roblox

Open your sketch in the Processing IDE and choose **Sketch > Export for Roblox...**

This writes a single `.lua` file with the backend require boilerplate prepended. Drop it into a LocalScript in Roblox Studio. You need two ModuleScripts in ReplicatedStorage:
- `Processing` — `mode/lua/processing.lua`
- `ProcessingBackendRoblox` — `mode/lua/backends/roblox.lua`

## LOVE2D

Two ways to run a sketch in LOVE2D:

**From the IDE:** Choose **Sketch > Export for LOVE2D...** and pick an output
folder. The exporter writes a self-contained game folder:

```
MySketch-love2d/
  main.lua          ← loads the sketch and drives the LOVE2D event loop
  conf.lua          ← minimal LOVE2D config (hidden window until size() runs)
  MySketch.lua      ← your sketch source
  processing/       ← processing.lua + backends/love2d.lua
```

Then run it with `love MySketch-love2d/`.

**From the repo directly** (development): `main.lua` at the repo root accepts
a `--sketch` argument:

```sh
love . --sketch examples/Basics/Bouncing\ Balls/Bouncing\ Balls.lua
love . --sketch examples/Basics/Paint/Paint.lua
love . --sketch "examples/Export/LOVE2D Starter/LOVE2D Starter.lua"
```

Either way, the sketch file is unchanged — same `.lua` source in the IDE,
LOVE2D, and Roblox.

## License

MIT
