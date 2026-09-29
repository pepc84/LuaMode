;;; Processing Lua Mode: tree-sitter highlights
;;; Targets the tree-sitter-lua grammar pinned by java-tree-sitter v1.9.1
;;; (Azganoth/tree-sitter-lua). Node names differ from other Lua grammars.

;; ── Comments ──────────────────────────────────────────────────────────────
(comment) @comment

;; ── Strings ───────────────────────────────────────────────────────────────
(string) @string

;; ── Numbers ───────────────────────────────────────────────────────────────
(number) @number

;; ── Booleans / nil ────────────────────────────────────────────────────────
(true)  @boolean
(false) @boolean
(nil)   @keyword
(break_statement) @keyword

;; ── Keywords ──────────────────────────────────────────────────────────────
[
  "and" "do" "else" "elseif" "end" "for" "function"
  "goto" "if" "in" "local" "not" "or"
  "repeat" "return" "then" "until" "while"
] @keyword

;; ── Operators ─────────────────────────────────────────────────────────────
[
  "+" "-" "*" "/" "//" "%" "^" "#"
  "&" "|" "~" "<<" ">>"
  "==" "~=" "<" ">" "<=" ">="
  "=" "." ".."
] @operator

(vararg_expression) @variable.builtin

;; ── Punctuation ───────────────────────────────────────────────────────────
[ "(" ")" "[" "]" "{" "}" ] @punctuation.bracket
[ "," ";" ":" "::" ]        @punctuation.delimiter

;; ── Function definitions ──────────────────────────────────────────────────
(function_definition_statement
  name: (identifier) @function)

(function_definition_statement
  name: (variable field: (identifier) @function))

(function_definition_statement
  name: (variable method: (identifier) @method))

(local_function_definition_statement
  name: (identifier) @function)

;; ── Processing lifecycle — @function.builtin ──────────────────────────────
((identifier) @function.builtin
  (#match? @function.builtin
    "^(setup|draw|mousePressed|mouseReleased|mouseClicked|mouseMoved|mouseDragged|keyPressed|keyReleased|keyTyped)$"))

;; ── Processing drawing API — @function.builtin ────────────────────────────
((identifier) @function.builtin
  (#match? @function.builtin
    "^(size|background|fill|noFill|stroke|noStroke|strokeWeight|strokeCap|strokeJoin|rect|ellipse|circle|arc|line|point|triangle|quad|bezier|curve|beginShape|endShape|vertex|bezierVertex|curveVertex|image|loadImage|tint|noTint|text|textSize|textAlign|textFont|loadFont)$"))

;; ── Processing transform / stack — @function.builtin ─────────────────────
((identifier) @function.builtin
  (#match? @function.builtin
    "^(push|pop|pushMatrix|popMatrix|pushStyle|popStyle|translate|rotate|scale|shearX|shearY|resetMatrix)$"))

;; ── Processing color — @function.builtin ─────────────────────────────────
((identifier) @function.builtin
  (#match? @function.builtin
    "^(colorMode|color|red|green|blue|alpha|hue|saturation|brightness|lerpColor)$"))

;; ── Processing math — @function.builtin ──────────────────────────────────
((identifier) @function.builtin
  (#match? @function.builtin
    "^(map|lerp|constrain|dist|mag|norm|sq|sqrt|pow|abs|ceil|floor|round|min|max|sin|cos|tan|asin|acos|atan|atan2|degrees|radians|noise|noiseDetail|noiseSeed|random|randomSeed|randomGaussian)$"))

;; ── Processing time / control — @function.builtin ────────────────────────
((identifier) @function.builtin
  (#match? @function.builtin
    "^(millis|second|minute|hour|day|month|year|frameRate|loop|noLoop|redraw|exit)$"))

;; ── Processing environment globals — @variable.builtin ───────────────────
((identifier) @variable.builtin
  (#match? @variable.builtin
    "^(width|height|mouseX|mouseY|pmouseX|pmouseY|frameCount|focused|displayWidth|displayHeight|mouseButton|mouseIsPressed|key|keyCode|keyIsPressed)$"))

;; ── Processing constants — @variable.builtin ─────────────────────────────
((identifier) @variable.builtin
  (#match? @variable.builtin
    "^(PI|TWO_PI|HALF_PI|QUARTER_PI|TAU|E|LEFT|RIGHT|CENTER|TOP|BOTTOM|BASELINE|RADIUS|CORNER|CORNERS|OPEN|CLOSE|POINTS|LINES|TRIANGLES|TRIANGLE_STRIP|TRIANGLE_FAN|QUADS|QUAD_STRIP|POLYGON|RGB|HSB|ROUND|SQUARE|PROJECT|MITER|BEVEL|UP|DOWN|ENTER|RETURN|BACKSPACE|TAB|DELETE|ESC|CODED|ALT|CONTROL|SHIFT)$"))

;; ── General function calls ────────────────────────────────────────────────
(call function: (variable name: (identifier) @function))
(call function: (variable field: (identifier) @function))
(call function: (variable method: (identifier) @method))

;; ── Variables / identifiers ───────────────────────────────────────────────
(identifier) @variable
