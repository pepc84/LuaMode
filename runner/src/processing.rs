//! Adapter between the Lua bindings in main.rs and RustMode's engine.
//!
//! main.rs was written against a libprocessing-style API: colors arrive
//! normalized to 0..1 (see unpack_color), constants use Lua-side numbering
//! (LEFT 0, RIGHT 1, CENTER 2, ...), and a few calls have different arities
//! than the engine's Rust-facing API. Everything is translated here so the
//! bindings stay readable and the engine stays Processing-shaped for RustMode.

use std::sync::atomic::{AtomicU32, Ordering};

pub use engine::App;

// ── Input ─────────────────────────────────────────────────────────────────

pub fn mouse_x() -> f32  { engine::mouse_x() }
pub fn mouse_y() -> f32  { engine::mouse_y() }
pub fn pmouse_x() -> f32 { engine::pmouse_x() }
pub fn pmouse_y() -> f32 { engine::pmouse_y() }
pub fn mouse_is_pressed() -> bool { engine::mouse_pressed_global() }
pub fn key_is_pressed() -> bool   { engine::key_pressed_global() }
pub fn key_char() -> char         { engine::key() }

/// Lua-side button numbering: LEFT 0, RIGHT 1, CENTER 2.
pub fn mouse_button() -> i32 {
    match engine::mouse_button() { 39 => 1, 3 => 2, _ => 0 }
}

/// Translate the engine's key codes (minifb) to the Processing / Java codes
/// the Lua constants use (UP 38, DOWN 40, ENTER 10, ...). Printable keys
/// report their character code, like Processing.
pub fn key_code() -> u32 {
    let k = engine::key_code();
    match k {
        k if k == engine::UP        => 38,
        k if k == engine::DOWN      => 40,
        k if k == engine::LEFT      => 37,
        k if k == engine::RIGHT     => 39,
        k if k == engine::ENTER     => 10,
        k if k == engine::BACKSPACE => 8,
        k if k == engine::DELETE    => 127,
        k if k == engine::ESCAPE    => 27,
        k if k == engine::TAB       => 9,
        k if k == engine::SHIFT     => 16,
        k if k == engine::CONTROL   => 17,
        0 => 0,
        _ => engine::key() as u32,
    }
}

// ── Environment ───────────────────────────────────────────────────────────

pub fn size(w: u32, h: u32)  { engine::size(w, h) }
pub fn width() -> f32        { engine::width() }
pub fn height() -> f32       { engine::height() }
pub fn frame_count() -> u64  { engine::frame_count() }
pub fn frame_rate() -> f32   { engine::current_frame_rate() }
pub fn set_frame_rate(fps: f32) { engine::frame_rate(fps) }
pub fn millis() -> u64       { engine::millis() }
pub fn set_loop(on: bool)    { if on { engine::r#loop() } else { engine::no_loop() } }
pub fn redraw()              { engine::redraw() }
pub fn exit()                { engine::exit() }
pub fn show_cursor()         { engine::cursor() }
pub fn hide_cursor()         { engine::no_cursor() }
pub fn println(s: &str)      { engine::println(s) }

// ── Color ─────────────────────────────────────────────────────────────────
//
// main.rs's unpack_color() calls resolve_color() on the raw arguments, so
// background/fill/stroke/tint below already receive RGBA in 0..255.

const RGB: u32 = 1;
const HSB: u32 = 3;
static COLOR_MODE: AtomicU32 = AtomicU32::new(RGB);
// per-channel maxima as f32 bits: [c1, c2, c3, alpha], default 255 each
static COLOR_MAX: [AtomicU32; 4] = [
    AtomicU32::new(0x437f0000), AtomicU32::new(0x437f0000),
    AtomicU32::new(0x437f0000), AtomicU32::new(0x437f0000),
];

/// Processing semantics: colorMode(m) keeps the ranges, colorMode(m, max)
/// sets all four, colorMode(m, a, b, c) sets the three color channels and
/// keeps alpha, colorMode(m, a, b, c, d) sets all four.
pub fn color_mode(mode: i32, maxes: &[f32]) {
    COLOR_MODE.store(if mode == HSB as i32 { HSB } else { RGB }, Ordering::Relaxed);
    let set = |i: usize, v: f32| COLOR_MAX[i].store(if v > 0.0 { v } else { 255.0 }.to_bits(), Ordering::Relaxed);
    match maxes.len() {
        0 => {}
        1 | 2 => for i in 0..4 { set(i, maxes[0]) },
        3 => for i in 0..3 { set(i, maxes[i]) },
        _ => for i in 0..4 { set(i, maxes[i]) },
    }
}

/// Turn the raw numbers from background()/fill()/stroke()/tint() into RGBA
/// 0..255, honouring colorMode:
///   (v) gray (brightness in HSB) · (v, a) gray + alpha
///   (c1, c2, c3) · (c1, c2, c3, a)
pub fn resolve_color(v: &[f32]) -> [f32; 4] {
    let m = |i: usize| f32::from_bits(COLOR_MAX[i].load(Ordering::Relaxed));
    let unit = |x: f32, i: usize| (x / m(i)).clamp(0.0, 1.0);
    let hsb = COLOR_MODE.load(Ordering::Relaxed) == HSB;
    let (rgb, alpha) = match v.len() {
        0 => ((0.0, 0.0, 0.0), 1.0),
        1 | 2 => {
            // a lone value is gray: in HSB that's brightness, in RGB all three
            let g = unit(v[0], if hsb { 2 } else { 0 });
            ((g, g, g), if v.len() == 2 { unit(v[1], 3) } else { 1.0 })
        }
        n => {
            let (a, b, c) = (unit(v[0], 0), unit(v[1], 1), unit(v[2], 2));
            (if hsb { hsb_to_rgb(a, b, c) } else { (a, b, c) },
             if n >= 4 { unit(v[3], 3) } else { 1.0 })
        }
    };
    [rgb.0 * 255.0, rgb.1 * 255.0, rgb.2 * 255.0, alpha * 255.0]
}

fn hsb_to_rgb(h: f32, s: f32, v: f32) -> (f32, f32, f32) {
    let h = (h.fract() + 1.0).fract() * 6.0;
    let i = h.floor();
    let f = h - i;
    let (p, q, t) = (v * (1.0 - s), v * (1.0 - s * f), v * (1.0 - s * (1.0 - f)));
    match i as i32 {
        0 => (v, t, p), 1 => (q, v, p), 2 => (p, v, t),
        3 => (p, q, v), 4 => (t, p, v), _ => (v, p, q),
    }
}

pub fn background(r: f32, g: f32, b: f32, a: f32) { engine::background_rgba(r, g, b, a) }
pub fn fill(r: f32, g: f32, b: f32, a: f32) { engine::fill_rgba(r, g, b, a) }
pub fn stroke(r: f32, g: f32, b: f32, a: f32) { engine::stroke_rgba(r, g, b, a) }
pub fn tint(r: f32, g: f32, b: f32, a: f32) { engine::tint_rgba(r, g, b, a) }
pub fn no_fill()   { engine::no_fill() }
pub fn no_stroke() { engine::no_stroke() }
pub fn no_tint()   { engine::no_tint() }
pub fn stroke_weight(w: f32) { engine::stroke_weight(w) }
pub fn stroke_cap(cap: i32)   { engine::stroke_cap(cap.max(0) as u32) }
pub fn stroke_join(join: i32) { engine::stroke_join(join.max(0) as u32) }

pub fn lerp_color(r1: f32, g1: f32, b1: f32, r2: f32, g2: f32, b2: f32, t: f32) -> (f32, f32, f32) {
    let t = t.clamp(0.0, 1.0);
    (r1 + (r2 - r1) * t, g1 + (g2 - g1) * t, b1 + (b2 - b1) * t)
}

// ── Shapes ────────────────────────────────────────────────────────────────

pub fn rect(x: f32, y: f32, w: f32, h: f32)    { engine::rect(x, y, w, h) }
pub fn ellipse(x: f32, y: f32, w: f32, h: f32) { engine::ellipse(x, y, w, h) }
pub fn circle(x: f32, y: f32, d: f32)          { engine::circle(x, y, d) }
pub fn line(x1: f32, y1: f32, x2: f32, y2: f32) { engine::line(x1, y1, x2, y2) }
pub fn point(x: f32, y: f32)                   { engine::point(x, y) }
pub fn triangle(x1: f32, y1: f32, x2: f32, y2: f32, x3: f32, y3: f32) {
    engine::triangle(x1, y1, x2, y2, x3, y3)
}
pub fn quad(x1: f32, y1: f32, x2: f32, y2: f32, x3: f32, y3: f32, x4: f32, y4: f32) {
    engine::quad(x1, y1, x2, y2, x3, y3, x4, y4)
}
/// The engine draws pie-style arcs; the mode argument is accepted and ignored.
pub fn arc(x: f32, y: f32, w: f32, h: f32, start: f32, stop: f32, _mode: i32) {
    engine::arc(x, y, w, h, start, stop)
}
pub fn bezier(x1: f32, y1: f32, cx1: f32, cy1: f32, cx2: f32, cy2: f32, x2: f32, y2: f32) {
    engine::bezier(x1, y1, cx1, cy1, cx2, cy2, x2, y2)
}
pub fn curve(cx1: f32, cy1: f32, x1: f32, y1: f32, x2: f32, y2: f32, cx2: f32, cy2: f32) {
    engine::curve(cx1, cy1, x1, y1, x2, y2, cx2, cy2)
}

/// Lua kinds: POINTS 0, LINES 1, TRIANGLES 4, TRIANGLE_STRIP 5,
/// TRIANGLE_FAN 6, QUADS 7, QUAD_STRIP 8, anything else polygon.
pub fn begin_shape(kind: i32) {
    let k = match kind {
        0 => engine::POINTS, 1 => engine::LINES, 4 => engine::TRIANGLES,
        5 => engine::TRIANGLE_STRIP, 6 => engine::TRIANGLE_FAN,
        7 => engine::QUADS, 8 => engine::QUAD_STRIP, _ => engine::POLYGON,
    };
    engine::begin_shape_kind(k)
}
/// Lua CLOSE is 1.
pub fn end_shape(mode: i32) { engine::end_shape_mode(if mode == 1 { engine::CLOSE } else { 0 }) }
pub fn vertex(x: f32, y: f32) { engine::vertex(x, y) }
pub fn bezier_vertex(cx1: f32, cy1: f32, cx2: f32, cy2: f32, x: f32, y: f32) {
    engine::bezier_vertex(cx1, cy1, cx2, cy2, x, y)
}
pub fn curve_vertex(x: f32, y: f32) { engine::curve_vertex(x, y) }

// ── Transform / style ─────────────────────────────────────────────────────

pub fn push_matrix()  { engine::push_matrix() }
pub fn pop_matrix()   { engine::pop_matrix() }
pub fn push_style()   { engine::push_style() }
pub fn pop_style()    { engine::pop_style() }
pub fn translate(x: f32, y: f32) { engine::translate(x, y) }
pub fn rotate(a: f32)            { engine::rotate(a) }
pub fn scale(x: f32, y: f32)     { engine::scale_xy(x, y) }
pub fn scale_uniform(s: f32)     { engine::scale(s) }
pub fn reset_matrix()            { engine::reset_matrix() }

// ── Text ──────────────────────────────────────────────────────────────────

pub fn text(s: &str, x: f32, y: f32) { engine::text(s, x, y) }
pub fn text_size(sz: f32)            { engine::text_size(sz) }
pub fn text_width(s: &str) -> f32    { engine::text_width(s) }
pub fn text_ascent() -> f32          { engine::text_ascent() }
pub fn text_descent() -> f32         { engine::text_descent() }

/// Lua constants: LEFT 0, RIGHT 1, CENTER 2, TOP 3, BOTTOM 4, and anything
/// else vertical (BASELINE) is the Processing default, baseline.
pub fn text_align(h: i32, v: i32) {
    let hh = match h { 1 => engine::ALIGN_RIGHT, 2 => engine::ALIGN_CENTER, _ => engine::ALIGN_LEFT };
    let vv = match v { 2 => engine::ALIGN_CENTER, 3 => engine::ALIGN_TOP, 4 => engine::ALIGN_BOTTOM, _ => engine::ALIGN_BASELINE };
    engine::text_align(hh, vv)
}

// ── Math ──────────────────────────────────────────────────────────────────

pub fn noise1(x: f32) -> f32               { engine::noise(x) }
pub fn noise2(x: f32, y: f32) -> f32       { engine::noise2(x, y) }
pub fn noise3(x: f32, y: f32, z: f32) -> f32 { engine::noise3(x, y, z) }
pub fn noise_seed(seed: u64)               { engine::noise_seed(seed) }
pub fn noise_detail(oct: i32, fall: f32)   { engine::noise_detail(oct.max(0) as u32, fall) }
pub fn random1() -> f32                    { engine::random(1.0) }
pub fn random_seed(seed: u64)              { engine::random_seed(seed) }
pub fn random_gaussian() -> f32            { engine::random_gaussian() }

// ── Images ────────────────────────────────────────────────────────────────

pub fn load_image(path: &str) -> u64 { engine::load_image(path) }
pub fn image(id: u64, x: f32, y: f32) { engine::image(id, x, y) }
pub fn image_sized(id: u64, x: f32, y: f32, w: f32, h: f32) { engine::image_sized(id, x, y, w, h) }

pub fn save_frame(name: &str) { engine::save_frame(name) }
