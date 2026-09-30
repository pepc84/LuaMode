/// luamode-runner
///
/// A thin Rust binary that:
///   1. Parses one CLI argument: the path to a combined .lua sketch file.
///   2. Registers every Processing function and constant as a Lua global.
///   3. Loads and executes the sketch file, which defines `setup` and `draw`.
///   4. Drives the event loop via `processing::App`, which internally uses
///      glfw::Runner — the same path all native Processing sketches use.
///
/// This is the architecturally clean path for LuaMode's IDE backend.
/// `processing_surface_create` (the C FFI) needs an existing native window
/// handle, so it cannot be called from LuaJIT directly.  Using the Rust App
/// builder side-steps that entirely: the runner is a first-class processing
/// sketch that happens to be driven by Lua.
///
/// Error output follows the standard Lua format:
///   <filename>:<line>: <message>
/// LuaBuild.java's LuaErrorMapper understands this format and maps line
/// numbers back to the correct sketch tab.

use mlua::prelude::*;
mod processing;
use processing::App;
use std::{
    env,
    fs,
    path::PathBuf,
};

// ── Shared state threaded through setup/draw ──────────────────────────────

struct RunnerState {
    lua:    Lua,
    /// Width/height last set by size() — read back after setup() to inform the App.
    width:  u32,
    height: u32,

    // Previous-frame input state — used to detect transitions and fire
    // Lua callbacks (mousePressed, mouseReleased, keyPressed, keyReleased).
    // Processing fires these once per event, not every frame the button is held.
    prev_mouse_pressed: bool,
    prev_key_pressed:   bool,
    prev_mouse_button:  i32,
    prev_key_code:      i32,
}

// ── Entry point ───────────────────────────────────────────────────────────

fn main() {
    let sketch_path: PathBuf = env::args()
        .nth(1)
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            eprintln!("usage: luamode-runner <sketch.lua>");
            std::process::exit(1);
        });

    let sketch_src = fs::read_to_string(&sketch_path).unwrap_or_else(|e| {
        eprintln!("{}: {}", sketch_path.display(), e);
        std::process::exit(1);
    });

    let path_str = sketch_path.to_string_lossy().into_owned();

    let lua = Lua::new();
    register_globals(&lua).unwrap_or_else(|e| {
        eprintln!("{}", e);
        std::process::exit(1);
    });

    // Execute the sketch.  This registers the user's setup/draw/event functions
    // as Lua globals but does not call them yet — the App builder does that.
    // We set the chunk name to the sketch path so Lua error messages include it:
    //   /tmp/processing_lua_XYZ.lua:12: attempt to index nil value
    // LuaErrorMapper in Java strips the path and maps the line number back
    // to the correct sketch tab.
    lua.load(&sketch_src)
        .set_name(&path_str)
        .exec()
        .unwrap_or_else(|e| {
            eprintln!("{}", e);
            std::process::exit(1);
        });

    let state = RunnerState {
        lua,
        width:  640,
        height: 480,
        prev_mouse_pressed: false,
        prev_key_pressed:   false,
        prev_mouse_button:  0,
        prev_key_code:      0,
    };

    // App::new takes (state, setup_fn, draw_fn) matching the real libprocessing
    // App builder API.  All event detection happens inside draw_fn by comparing
    // previous vs current input state — see fire_input_callbacks() below.
    App::new(state, setup_fn, draw_fn).run();
}

// ── App callbacks ─────────────────────────────────────────────────────────

fn setup_fn(s: &mut RunnerState) {
    let setup: Option<LuaFunction> = s.lua.globals().get("setup").ok().flatten();
    if let Some(f) = setup {
        if let Err(e) = f.call::<_, ()>(()) {
            eprintln!("{}", e);
        }
    }
    // After setup runs, size() may have updated width/height in Lua globals;
    // sync them back into RunnerState so the App knows the window size.
    s.width  = s.lua.globals().get("width").unwrap_or(640);
    s.height = s.lua.globals().get("height").unwrap_or(480);
}

fn draw_fn(s: &mut RunnerState) {
    // 1. Sync globals so the sketch reads current values.
    sync_frame_globals(&s.lua);

    // 2. Fire any input callbacks that became true since last frame.
    //    This gives Processing-compatible behaviour: mousePressed() fires once
    //    on the frame the button goes down, not every frame it's held.
    fire_input_callbacks(s);

    // 3. Call the user's draw().
    call_lua_callback(&s.lua, "draw");
}

// ── Input callback edge detection ─────────────────────────────────────────
//
// libprocessing's App builder does not expose an event hook in its public API
// (setup_fn + draw_fn only).  We detect transitions by comparing current
// frame's input state against the previous frame's — exactly one callback
// fires per edge, matching real Processing behaviour.

fn fire_input_callbacks(s: &mut RunnerState) {
    let mouse_pressed  = processing::mouse_is_pressed();
    let mouse_button   = processing::mouse_button() as i32;
    let key_pressed    = processing::key_is_pressed();
    let key_code       = processing::key_code() as i32;

    // Mouse button down
    if mouse_pressed && !s.prev_mouse_pressed {
        call_lua_callback(&s.lua, "mousePressed");
    }
    // Mouse button up
    if !mouse_pressed && s.prev_mouse_pressed {
        call_lua_callback(&s.lua, "mouseReleased");
        // mouseClicked fires on the same frame as release
        call_lua_callback(&s.lua, "mouseClicked");
    }

    // Mouse moved / dragged — we track by comparing mouse position globals.
    // sync_frame_globals already set mouseX/mouseY and pmouseX/pmouseY.
    let mx:  f32 = s.lua.globals().get("mouseX").unwrap_or(0.0);
    let my:  f32 = s.lua.globals().get("mouseY").unwrap_or(0.0);
    let pmx: f32 = s.lua.globals().get("pmouseX").unwrap_or(0.0);
    let pmy: f32 = s.lua.globals().get("pmouseY").unwrap_or(0.0);
    if mx != pmx || my != pmy {
        if mouse_pressed {
            call_lua_callback(&s.lua, "mouseDragged");
        } else {
            call_lua_callback(&s.lua, "mouseMoved");
        }
    }

    // Key down (new key or same key held is one event per physical press)
    if key_pressed && (!s.prev_key_pressed || key_code != s.prev_key_code) {
        call_lua_callback(&s.lua, "keyPressed");
    }
    // Key up
    if !key_pressed && s.prev_key_pressed {
        call_lua_callback(&s.lua, "keyReleased");
        call_lua_callback(&s.lua, "keyTyped");
    }

    // Save state for next frame.
    s.prev_mouse_pressed = mouse_pressed;
    s.prev_mouse_button  = mouse_button;
    s.prev_key_pressed   = key_pressed;
    s.prev_key_code      = key_code;
}

fn call_lua_callback(lua: &Lua, name: &str) {
    let f: Option<LuaFunction> = lua.globals().get(name).ok().flatten();
    if let Some(f) = f {
        if let Err(e) = f.call::<_, ()>(()) {
            eprintln!("{}", e);
        }
    }
}

/// Copies libprocessing's current frame state into Lua globals.
/// Called at the top of every draw frame, before the user's draw().
fn sync_frame_globals(lua: &Lua) {
    let g = lua.globals();
    let _ = g.set("mouseX",       processing::mouse_x());
    let _ = g.set("mouseY",       processing::mouse_y());
    let _ = g.set("pmouseX",      processing::pmouse_x());
    let _ = g.set("pmouseY",      processing::pmouse_y());
    // mousePressed / keyPressed are also the names of the event callbacks.
    // Lua can't hold both, so the state lives in mouseIsPressed / keyIsPressed
    // (like p5.js), and the old names are only set when the sketch hasn't
    // defined a function with that name.
    let mp = processing::mouse_is_pressed();
    let kp = processing::key_is_pressed();
    let _ = g.set("mouseIsPressed", mp);
    let _ = g.set("keyIsPressed",   kp);
    if !matches!(g.get::<_, LuaValue>("mousePressed"), Ok(LuaValue::Function(_))) {
        let _ = g.set("mousePressed", mp);
    }
    let _ = g.set("mouseButton",  processing::mouse_button() as i32);
    if !matches!(g.get::<_, LuaValue>("keyPressed"), Ok(LuaValue::Function(_))) {
        let _ = g.set("keyPressed", kp);
    }
    let _ = g.set("key",          processing::key_char().to_string());
    let _ = g.set("keyCode",      processing::key_code() as i32);
    let _ = g.set("frameCount",   processing::frame_count());
    let _ = g.set("frameRate",    processing::frame_rate());
    let _ = g.set("width",        processing::width());
    let _ = g.set("height",       processing::height());
}

// ── Global registration ───────────────────────────────────────────────────
//
// Every Processing API function and constant that `processing.lua` (the Lua
// library) delegates to its backend is registered here as a Lua global.
// The Lua library calls `Backend.ellipse(x,y,w,h)` etc.; the runner backend
// is the globals table itself, so `ellipse(x,y,w,h)` calls straight through.
//
// Naming follows the Processing API exactly (lowercase, camelCase as needed).

fn register_globals(lua: &Lua) -> LuaResult<()> {
    let g = lua.globals();

    // ── size / dimensions ─────────────────────────────────────────────────
    // size() is intercepted during setup to configure the window.
    // After setup the width/height globals are set by the App.
    // We register a stub here; LuaBuild's generated header overrides it.
    g.set("size", lua.create_function(|lua, (w, h): (u32, u32)| {
        processing::size(w, h);
        lua.globals().set("width",  w)?;
        lua.globals().set("height", h)?;
        Ok(())
    })?)?;

    g.set("width",  640_u32)?;
    g.set("height", 480_u32)?;

    // ── Frame state (updated each draw by the App) ────────────────────────
    g.set("frameCount",    0_u32)?;
    g.set("frameRate",     60_f32)?;
    g.set("mouseX",        0_f32)?;
    g.set("mouseY",        0_f32)?;
    g.set("pmouseX",       0_f32)?;
    g.set("pmouseY",       0_f32)?;
    g.set("mousePressed",   false)?;
    g.set("keyPressed",     false)?;
    g.set("mouseIsPressed", false)?;
    g.set("keyIsPressed",   false)?;
    g.set("key",           "")?;
    g.set("keyCode",       0_i32)?;

    // ── Constants ─────────────────────────────────────────────────────────
    use std::f32::consts::PI;
    g.set("PI",       PI)?;
    g.set("TWO_PI",   PI * 2.0)?;
    g.set("HALF_PI",  PI / 2.0)?;
    g.set("TAU",      PI * 2.0)?;
    g.set("QUARTER_PI", PI / 4.0)?;
    // Alignment / modes
    g.set("LEFT",    0_i32)?;
    g.set("RIGHT",   1_i32)?;
    g.set("CENTER",  2_i32)?;
    g.set("TOP",     3_i32)?;
    g.set("BOTTOM",  4_i32)?;
    g.set("OPEN",    0_i32)?;
    g.set("CLOSE",   1_i32)?;
    // Color modes
    g.set("RGB",     1_i32)?;
    g.set("HSB",     3_i32)?;
    // Shape kinds
    g.set("POINTS",       0_i32)?;
    g.set("LINES",        1_i32)?;
    g.set("TRIANGLES",    4_i32)?;
    g.set("TRIANGLE_FAN", 6_i32)?;
    g.set("QUADS",        7_i32)?;
    g.set("QUAD_STRIP",   8_i32)?;
    g.set("POLYGON",      9_i32)?;
    // Stroke caps / joins
    g.set("ROUND",   0_i32)?;
    g.set("SQUARE",  1_i32)?;
    g.set("PROJECT", 2_i32)?;
    g.set("MITER",   3_i32)?;
    g.set("BEVEL",   4_i32)?;
    // Key codes
    g.set("BACKSPACE", 8_i32)?;
    g.set("TAB",       9_i32)?;
    g.set("ENTER",     10_i32)?;
    g.set("RETURN",    13_i32)?;
    g.set("ESC",       27_i32)?;
    g.set("DELETE",    127_i32)?;
    g.set("CODED",     0xFFFF_i32)?;
    g.set("UP",        38_i32)?;
    g.set("DOWN",      40_i32)?;
    g.set("SHIFT",     16_i32)?;
    g.set("CONTROL",   17_i32)?;
    g.set("ALT",       18_i32)?;
    // Mouse buttons
    g.set("NONE",    0_i32)?;
    // LEFT already set above

    // ── Background / color ────────────────────────────────────────────────
    g.set("background", lua.create_function(|_, args: LuaMultiValue| {
        let c = unpack_color(args);
        processing::background(c[0], c[1], c[2], c[3]);
        Ok(())
    })?)?;

    g.set("fill", lua.create_function(|_, args: LuaMultiValue| {
        let c = unpack_color(args);
        processing::fill(c[0], c[1], c[2], c[3]);
        Ok(())
    })?)?;

    g.set("noFill", lua.create_function(|_, ()| {
        processing::no_fill();
        Ok(())
    })?)?;

    g.set("stroke", lua.create_function(|_, args: LuaMultiValue| {
        let c = unpack_color(args);
        processing::stroke(c[0], c[1], c[2], c[3]);
        Ok(())
    })?)?;

    g.set("noStroke", lua.create_function(|_, ()| {
        processing::no_stroke();
        Ok(())
    })?)?;

    g.set("strokeWeight", lua.create_function(|_, w: f32| {
        processing::stroke_weight(w);
        Ok(())
    })?)?;

    g.set("strokeCap", lua.create_function(|_, cap: i32| {
        processing::stroke_cap(cap);
        Ok(())
    })?)?;

    g.set("strokeJoin", lua.create_function(|_, join: i32| {
        processing::stroke_join(join);
        Ok(())
    })?)?;

    // colorMode(mode), colorMode(mode, max), colorMode(mode, m1, m2, m3[, mA])
    g.set("colorMode", lua.create_function(|_, args: LuaMultiValue| {
        let v: Vec<f32> = args.iter().filter_map(|a| a.to_num()).collect();
        let mode = v.first().copied().unwrap_or(1.0) as i32;
        processing::color_mode(mode, &v[1.min(v.len())..]);
        Ok(())
    })?)?;

    g.set("lerpColor", lua.create_function(|_, (r1,g1,b1, r2,g2,b2, t): (f32,f32,f32,f32,f32,f32,f32)| {
        let (r,g,b) = processing::lerp_color(r1,g1,b1, r2,g2,b2, t);
        // Return as table {r,g,b}
        Ok(LuaMultiValue::from_vec(vec![
            LuaValue::Number(r as f64),
            LuaValue::Number(g as f64),
            LuaValue::Number(b as f64),
        ]))
    })?)?;

    // ── Shapes ────────────────────────────────────────────────────────────
    g.set("rect", lua.create_function(|_, (x,y,w,h): (f32,f32,f32,f32)| {
        processing::rect(x,y,w,h); Ok(())
    })?)?;

    g.set("ellipse", lua.create_function(|_, (x,y,w,h): (f32,f32,f32,f32)| {
        processing::ellipse(x,y,w,h); Ok(())
    })?)?;

    g.set("circle", lua.create_function(|_, (x,y,d): (f32,f32,f32)| {
        processing::circle(x,y,d); Ok(())
    })?)?;

    g.set("line", lua.create_function(|_, (x1,y1,x2,y2): (f32,f32,f32,f32)| {
        processing::line(x1,y1,x2,y2); Ok(())
    })?)?;

    g.set("point", lua.create_function(|_, (x,y): (f32,f32)| {
        processing::point(x,y); Ok(())
    })?)?;

    g.set("triangle", lua.create_function(|_, (x1,y1,x2,y2,x3,y3): (f32,f32,f32,f32,f32,f32)| {
        processing::triangle(x1,y1,x2,y2,x3,y3); Ok(())
    })?)?;

    g.set("quad", lua.create_function(|_, (x1,y1,x2,y2,x3,y3,x4,y4): (f32,f32,f32,f32,f32,f32,f32,f32)| {
        processing::quad(x1,y1,x2,y2,x3,y3,x4,y4); Ok(())
    })?)?;

    g.set("arc", lua.create_function(|_, (x,y,w,h,start,stop,mode): (f32,f32,f32,f32,f32,f32,Option<i32>)| {
        processing::arc(x,y,w,h,start,stop, mode.unwrap_or(0)); Ok(())
    })?)?;

    g.set("bezier", lua.create_function(|_, (x1,y1,cx1,cy1,cx2,cy2,x2,y2): (f32,f32,f32,f32,f32,f32,f32,f32)| {
        processing::bezier(x1,y1,cx1,cy1,cx2,cy2,x2,y2); Ok(())
    })?)?;

    g.set("curve", lua.create_function(|_, (cx1,cy1,x1,y1,x2,y2,cx2,cy2): (f32,f32,f32,f32,f32,f32,f32,f32)| {
        processing::curve(cx1,cy1,x1,y1,x2,y2,cx2,cy2); Ok(())
    })?)?;

    // ── Vertex / shape builders ───────────────────────────────────────────
    g.set("beginShape", lua.create_function(|_, kind: Option<i32>| {
        processing::begin_shape(kind.unwrap_or(9)); Ok(())
    })?)?;

    g.set("endShape", lua.create_function(|_, mode: Option<i32>| {
        processing::end_shape(mode.unwrap_or(0)); Ok(())
    })?)?;

    g.set("vertex", lua.create_function(|_, (x,y): (f32,f32)| {
        processing::vertex(x,y); Ok(())
    })?)?;

    g.set("bezierVertex", lua.create_function(|_, (cx1,cy1,cx2,cy2,x,y): (f32,f32,f32,f32,f32,f32)| {
        processing::bezier_vertex(cx1,cy1,cx2,cy2,x,y); Ok(())
    })?)?;

    g.set("curveVertex", lua.create_function(|_, (x,y): (f32,f32)| {
        processing::curve_vertex(x,y); Ok(())
    })?)?;

    // ── Transform ─────────────────────────────────────────────────────────
    g.set("push", lua.create_function(|_, ()| { processing::push_matrix(); Ok(()) })?)?;
    g.set("pop",  lua.create_function(|_, ()| { processing::pop_matrix();  Ok(()) })?)?;
    // Aliases for the longer names
    g.set("pushMatrix", lua.create_function(|_, ()| { processing::push_matrix(); Ok(()) })?)?;
    g.set("popMatrix",  lua.create_function(|_, ()| { processing::pop_matrix();  Ok(()) })?)?;
    g.set("pushStyle",  lua.create_function(|_, ()| { processing::push_style();  Ok(()) })?)?;
    g.set("popStyle",   lua.create_function(|_, ()| { processing::pop_style();   Ok(()) })?)?;

    g.set("translate", lua.create_function(|_, (x,y): (f32,f32)| {
        processing::translate(x,y); Ok(())
    })?)?;

    g.set("rotate", lua.create_function(|_, angle: f32| {
        processing::rotate(angle); Ok(())
    })?)?;

    g.set("scale", lua.create_function(|_, args: LuaMultiValue| {
        let vals: Vec<f32> = args.iter()
            .filter_map(|v| v.to_num())
            .collect();
        match vals.len() {
            1 => processing::scale_uniform(vals[0]),
            _ => processing::scale(vals[0], *vals.get(1).unwrap_or(&vals[0])),
        }
        Ok(())
    })?)?;

    g.set("resetMatrix", lua.create_function(|_, ()| {
        processing::reset_matrix(); Ok(())
    })?)?;

    // ── Text ──────────────────────────────────────────────────────────────
    g.set("text", lua.create_function(|_, (s, x, y): (String, f32, f32)| {
        processing::text(&s, x, y); Ok(())
    })?)?;

    g.set("textSize", lua.create_function(|_, sz: f32| {
        processing::text_size(sz); Ok(())
    })?)?;

    g.set("textAlign", lua.create_function(|_, (h, v): (i32, Option<i32>)| {
        processing::text_align(h, v.unwrap_or(3)); Ok(())
    })?)?;

    g.set("textWidth", lua.create_function(|_, s: String| {
        Ok(processing::text_width(&s))
    })?)?;

    g.set("textAscent", lua.create_function(|_, ()| {
        Ok(processing::text_ascent())
    })?)?;

    g.set("textDescent", lua.create_function(|_, ()| {
        Ok(processing::text_descent())
    })?)?;

    // ── Math ──────────────────────────────────────────────────────────────
    g.set("map", lua.create_function(|_, (v,a1,b1,a2,b2): (f32,f32,f32,f32,f32)| {
        Ok(a2 + (v - a1) / (b1 - a1) * (b2 - a2))
    })?)?;

    g.set("lerp", lua.create_function(|_, (a,b,t): (f32,f32,f32)| {
        Ok(a + (b - a) * t)
    })?)?;

    g.set("constrain", lua.create_function(|_, (v,lo,hi): (f32,f32,f32)| {
        Ok(v.clamp(lo, hi))
    })?)?;

    g.set("dist", lua.create_function(|_, (x1,y1,x2,y2): (f32,f32,f32,f32)| {
        Ok(((x2-x1).powi(2) + (y2-y1).powi(2)).sqrt())
    })?)?;

    g.set("mag", lua.create_function(|_, (x,y): (f32,f32)| {
        Ok((x*x + y*y).sqrt())
    })?)?;

    g.set("abs",   lua.create_function(|_, v: f32| Ok(v.abs()))?)?;
    g.set("floor", lua.create_function(|_, v: f32| Ok(v.floor()))?)?;
    g.set("ceil",  lua.create_function(|_, v: f32| Ok(v.ceil()))?)?;
    g.set("round", lua.create_function(|_, v: f32| Ok(v.round()))?)?;
    g.set("sqrt",  lua.create_function(|_, v: f32| Ok(v.sqrt()))?)?;
    g.set("sq",    lua.create_function(|_, v: f32| Ok(v * v))?)?;
    g.set("pow",   lua.create_function(|_, (b,e): (f32,f32)| Ok(b.powf(e)))?)?;
    g.set("log",   lua.create_function(|_, v: f32| Ok(v.ln()))?)?;
    g.set("exp",   lua.create_function(|_, v: f32| Ok(v.exp()))?)?;
    g.set("sin",   lua.create_function(|_, v: f32| Ok(v.sin()))?)?;
    g.set("cos",   lua.create_function(|_, v: f32| Ok(v.cos()))?)?;
    g.set("tan",   lua.create_function(|_, v: f32| Ok(v.tan()))?)?;
    g.set("asin",  lua.create_function(|_, v: f32| Ok(v.asin()))?)?;
    g.set("acos",  lua.create_function(|_, v: f32| Ok(v.acos()))?)?;
    g.set("atan",  lua.create_function(|_, v: f32| Ok(v.atan()))?)?;
    g.set("atan2", lua.create_function(|_, (y,x): (f32,f32)| Ok(y.atan2(x)))?)?;
    g.set("degrees", lua.create_function(|_, v: f32| Ok(v.to_degrees()))?)?;
    g.set("radians", lua.create_function(|_, v: f32| Ok(v.to_radians()))?)?;
    g.set("min",   lua.create_function(|_, (a,b): (f32,f32)| Ok(a.min(b)))?)?;
    g.set("max",   lua.create_function(|_, (a,b): (f32,f32)| Ok(a.max(b)))?)?;

    // noise / random
    g.set("noise", lua.create_function(|_, args: LuaMultiValue| {
        let vals: Vec<f32> = args.iter().filter_map(|v| v.to_num()).collect();
        Ok(match vals.len() {
            1 => processing::noise1(vals[0]),
            2 => processing::noise2(vals[0], vals[1]),
            _ => processing::noise3(vals[0], vals[1], *vals.get(2).unwrap_or(&0.0)),
        })
    })?)?;

    g.set("noiseSeed", lua.create_function(|_, seed: u64| {
        processing::noise_seed(seed); Ok(())
    })?)?;

    g.set("noiseDetail", lua.create_function(|_, (oct, fall): (i32, Option<f32>)| {
        processing::noise_detail(oct, fall.unwrap_or(0.5)); Ok(())
    })?)?;

    g.set("random", lua.create_function(|_, args: LuaMultiValue| {
        let vals: Vec<f32> = args.iter().filter_map(|v| v.to_num()).collect();
        Ok(match vals.len() {
            0 => processing::random1(),
            1 => processing::random1() * vals[0],
            _ => vals[0] + processing::random1() * (vals[1] - vals[0]),
        })
    })?)?;

    g.set("randomSeed", lua.create_function(|_, seed: u64| {
        processing::random_seed(seed); Ok(())
    })?)?;

    g.set("randomGaussian", lua.create_function(|_, ()| {
        Ok(processing::random_gaussian())
    })?)?;

    // ── Time / loop control ───────────────────────────────────────────────
    g.set("millis", lua.create_function(|_, ()| {
        Ok(processing::millis())
    })?)?;

    g.set("loop",   lua.create_function(|_, ()| { processing::set_loop(true);  Ok(()) })?)?;
    g.set("noLoop", lua.create_function(|_, ()| { processing::set_loop(false); Ok(()) })?)?;

    g.set("redraw", lua.create_function(|_, ()| {
        processing::redraw(); Ok(())
    })?)?;

    g.set("delay", lua.create_function(|_, ms: u64| {
        std::thread::sleep(std::time::Duration::from_millis(ms)); Ok(())
    })?)?;

    // frameRate(n) — set the target frame rate.
    // Note: `frameRate` is also a number global (current fps, updated each draw).
    // Because Lua can't overload the same name as both a number and a function,
    // we provide `setFrameRate(n)` for setting it and keep `frameRate` as a
    // read-only number.  Calling `frameRate(n)` directly also works and overrides
    // the global, but reads of `frameRate` will then return the function object
    // until the next sync.  Prefer `setFrameRate`.
    g.set("setFrameRate", lua.create_function(|_, fps: f32| {
        processing::set_frame_rate(fps); Ok(())
    })?)?;

    // cursor / noCursor
    g.set("cursor", lua.create_function(|_, ()| {
        processing::show_cursor(); Ok(())
    })?)?;

    g.set("noCursor", lua.create_function(|_, ()| {
        processing::hide_cursor(); Ok(())
    })?)?;

    // exit() — close the sketch window cleanly
    g.set("exit", lua.create_function(|_, ()| {
        processing::exit(); Ok(())
    })?)?;

    // println / print — route through Processing's console so output appears
    // in the IDE console pane rather than the raw terminal.  The Lua builtins
    // (io.write, print) still work but go to stdout.
    // Lua's own print() goes through C stdio, which is fully buffered when
    // stdout is a pipe (as it is under the IDE), so output would show up in
    // chunks or only at exit. Same formatting as Lua's print (tostring on each
    // argument, tab-separated), but written and flushed a line at a time.
    g.set("print", lua.create_function(|lua, args: LuaMultiValue| {
        let tostring: LuaFunction = lua.globals().get("tostring")?;
        let mut parts = Vec::with_capacity(args.len());
        for v in args {
            parts.push(tostring.call::<_, String>(v)?);
        }
        use std::io::Write;
        let mut out = std::io::stdout().lock();
        let _ = writeln!(out, "{}", parts.join("\t"));
        let _ = out.flush();
        Ok(())
    })?)?;

    g.set("println", lua.create_function(|_, s: Option<String>| {
        processing::println(&s.unwrap_or_default()); Ok(())
    })?)?;

    // ── Image ─────────────────────────────────────────────────────────────
    // Images are opaque handles (u64 IDs returned by processing::load_image).
    // We store them as Lua integers.
    g.set("loadImage", lua.create_function(|_, path: String| {
        Ok(processing::load_image(&path) as i64)
    })?)?;

    g.set("image", lua.create_function(|_, (id, x, y, w, h): (i64, f32, f32, Option<f32>, Option<f32>)| {
        match (w, h) {
            (Some(w), Some(h)) => processing::image_sized(id as u64, x, y, w, h),
            _                  => processing::image(id as u64, x, y),
        }
        Ok(())
    })?)?;

    g.set("tint", lua.create_function(|_, args: LuaMultiValue| {
        let c = unpack_color(args);
        processing::tint(c[0], c[1], c[2], c[3]); Ok(())
    })?)?;

    g.set("noTint", lua.create_function(|_, ()| { processing::no_tint(); Ok(()) })?)?;

    g.set("saveFrame", lua.create_function(|_, name: Option<String>| {
        processing::save_frame(&name.unwrap_or_else(|| "screen-####.png".into())); Ok(())
    })?)?;

    Ok(())
}

// ── Color unpacking ───────────────────────────────────────────────────────
//
// Processing color calls accept multiple arities:
//   background(v)           → grey
//   background(v, a)        → grey + alpha
//   background(r, g, b)     → rgb
//   background(r, g, b, a)  → rgba
//
// The values are interpreted in the current colorMode (RGB or HSB, with
// per-channel ranges) and come back as RGBA in 0..255.

fn unpack_color(args: LuaMultiValue) -> [f32; 4] {
    let vals: Vec<f32> = args.iter().filter_map(|v| v.to_num()).collect();
    processing::resolve_color(&vals)
}

// ── LuaValue helper ───────────────────────────────────────────────────────

/// Lua 5.4 numbers are either Integer or Number. mlua 0.9 has its own
/// `as_f32` that only accepts Number, so this is deliberately named `to_num`.
trait AsF32 {
    fn to_num(&self) -> Option<f32>;
}

impl AsF32 for LuaValue<'_> {
    fn to_num(&self) -> Option<f32> {
        match self {
            LuaValue::Number(n)  => Some(*n as f32),
            LuaValue::Integer(n) => Some(*n as f32),
            _                    => None,
        }
    }
}
