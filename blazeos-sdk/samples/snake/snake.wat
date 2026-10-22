;; Snake for BlazeOS, written straight in WebAssembly text (no toolchain beyond wat2wasm).
;; The same game as the web Snake: an 18 x 18 board, swipe or arrow keys to turn, eat to grow, best score saved.
;; Host calls ("syscalls") come from the "blazeos" module; see blazeos-sdk/README.md for the full list.
(module
  (import "blazeos" "clear"     (func $clear (param i32)))
  (import "blazeos" "rect"      (func $rect (param f32 f32 f32 f32 f32 i32)))
  (import "blazeos" "text"      (func $text (param i32 i32 f32 f32 f32 i32 i32)))
  (import "blazeos" "store_get" (func $store_get (param i32 i32 i32 i32) (result i32)))
  (import "blazeos" "store_set" (func $store_set (param i32 i32 i32 i32) (result i32)))
  (import "blazeos" "notify"    (func $notify (param i32 i32 i32 i32) (result i32)))
  (import "blazeos" "theme_dark" (func $theme_dark (result i32)))
  (import "blazeos" "random"    (func $random (result f64)))

  (memory (export "memory") 1 1)

  ;; Strings (offset, length)
  (data (i32.const 0)   "best")                        ;; 0,4
  (data (i32.const 16)  "Snake")                       ;; 16,5
  (data (i32.const 32)  "Score ")                      ;; 32,6
  (data (i32.const 48)  "  \c2\b7  Best ")             ;; 48,11
  (data (i32.const 64)  "Tap to play")                 ;; 64,11
  (data (i32.const 80)  "Game over \c2\b7 ")           ;; 80,13
  (data (i32.const 96)  "Paused \c2\b7 tap to go on")  ;; 96,23
  (data (i32.const 128) "New best in Snake")           ;; 128,17
  (data (i32.const 160) "You scored ")                 ;; 160,11
  (data (i32.const 176) "Swipe or use the arrow keys") ;; 176,27

  ;; 512: text scratch (256 bytes). 1024: the snake, 2 bytes per cell (x, y), head first.
  (global $N i32 (i32.const 18))
  (global $len (mut i32) (i32.const 0))
  (global $dx (mut i32) (i32.const 1))  (global $dy (mut i32) (i32.const 0))
  (global $nx (mut i32) (i32.const 1))  (global $ny (mut i32) (i32.const 0))
  (global $fx (mut i32) (i32.const 0))  (global $fy (mut i32) (i32.const 0))
  (global $score (mut i32) (i32.const 0))
  (global $best (mut i32) (i32.const 0))
  (global $state (mut i32) (i32.const 0))   ;; 0 ready, 1 playing, 2 over, 3 paused
  (global $resync (mut i32) (i32.const 1))
  (global $last (mut f64) (f64.const 0))
  (global $w (mut f32) (f32.const 360)) (global $h (mut f32) (f32.const 640))
  (global $cell (mut f32) (f32.const 18)) (global $ox (mut f32) (f32.const 0)) (global $oy (mut f32) (f32.const 0))

  (func $layout
    (local $side f32)
    (local.set $side (f32.min (f32.sub (global.get $w) (f32.const 24)) (f32.sub (global.get $h) (f32.const 150))))
    (global.set $cell (f32.floor (f32.div (local.get $side) (f32.const 18))))
    (if (f32.lt (global.get $cell) (f32.const 4)) (then (global.set $cell (f32.const 4))))
    (global.set $ox (f32.floor (f32.div (f32.sub (global.get $w) (f32.mul (global.get $cell) (f32.const 18))) (f32.const 2))))
    (global.set $oy (f32.const 104)))

  ;; Cells
  (func $cx (param $i i32) (result i32) (i32.load8_u (i32.add (i32.const 1024) (i32.shl (local.get $i) (i32.const 1)))))
  (func $cy (param $i i32) (result i32) (i32.load8_u (i32.add (i32.const 1025) (i32.shl (local.get $i) (i32.const 1)))))
  (func $set (param $i i32) (param $x i32) (param $y i32)
    (i32.store8 (i32.add (i32.const 1024) (i32.shl (local.get $i) (i32.const 1))) (local.get $x))
    (i32.store8 (i32.add (i32.const 1025) (i32.shl (local.get $i) (i32.const 1))) (local.get $y)))
  (func $on_snake (param $x i32) (param $y i32) (param $upto i32) (result i32)
    (local $i i32)
    (block $done (loop $l
      (br_if $done (i32.ge_u (local.get $i) (local.get $upto)))
      (if (i32.and (i32.eq (call $cx (local.get $i)) (local.get $x)) (i32.eq (call $cy (local.get $i)) (local.get $y))) (then (return (i32.const 1))))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br $l)))
    (i32.const 0))

  (func $rnd (result i32) (i32.trunc_f64_u (f64.mul (call $random) (f64.const 18))))
  (func $place_food
    (local $x i32) (local $y i32)
    (loop $again
      (local.set $x (i32.rem_u (call $rnd) (i32.const 18)))
      (local.set $y (i32.rem_u (call $rnd) (i32.const 18)))
      (br_if $again (call $on_snake (local.get $x) (local.get $y) (global.get $len))))
    (global.set $fx (local.get $x)) (global.set $fy (local.get $y)))

  (func $start
    (call $set (i32.const 0) (i32.const 8) (i32.const 9))
    (call $set (i32.const 1) (i32.const 7) (i32.const 9))
    (call $set (i32.const 2) (i32.const 6) (i32.const 9))
    (global.set $len (i32.const 3))
    (global.set $dx (i32.const 1)) (global.set $dy (i32.const 0))
    (global.set $nx (i32.const 1)) (global.set $ny (i32.const 0))
    (global.set $score (i32.const 0))
    (call $place_food)
    (global.set $state (i32.const 1))
    (global.set $resync (i32.const 1)))

  ;; Digits of $n at $p; returns the length.
  (func $itoa (param $n i32) (param $p i32) (result i32)
    (local $t i32) (local $k i32) (local $i i32)
    (if (i32.eqz (local.get $n)) (then (i32.store8 (local.get $p) (i32.const 48)) (return (i32.const 1))))
    (local.set $t (local.get $n))
    (loop $c (local.set $k (i32.add (local.get $k) (i32.const 1))) (local.set $t (i32.div_u (local.get $t) (i32.const 10))) (br_if $c (local.get $t)))
    (local.set $i (local.get $k))
    (local.set $t (local.get $n))
    (loop $w
      (local.set $i (i32.sub (local.get $i) (i32.const 1)))
      (i32.store8 (i32.add (local.get $p) (local.get $i)) (i32.add (i32.const 48) (i32.rem_u (local.get $t) (i32.const 10))))
      (local.set $t (i32.div_u (local.get $t) (i32.const 10)))
      (br_if $w (local.get $i)))
    (local.get $k))

  (func $atoi (param $p i32) (param $n i32) (result i32)
    (local $i i32) (local $v i32) (local $c i32)
    (block $d (loop $l
      (br_if $d (i32.ge_u (local.get $i) (local.get $n)))
      (local.set $c (i32.sub (i32.load8_u (i32.add (local.get $p) (local.get $i))) (i32.const 48)))
      (br_if $d (i32.gt_u (local.get $c) (i32.const 9)))
      (local.set $v (i32.add (i32.mul (local.get $v) (i32.const 10)) (local.get $c)))
      (local.set $i (i32.add (local.get $i) (i32.const 1)))
      (br $l)))
    (local.get $v))

  (func $game_over
    (local $n i32)
    (global.set $state (i32.const 2))
    (if (i32.gt_u (global.get $score) (global.get $best)) (then
      (global.set $best (global.get $score))
      (local.set $n (call $itoa (global.get $best) (i32.const 512)))
      (drop (call $store_set (i32.const 0) (i32.const 4) (i32.const 512) (local.get $n)))
      ;; "You scored N"
      (memory.copy (i32.const 600) (i32.const 160) (i32.const 11))
      (local.set $n (call $itoa (global.get $score) (i32.const 611)))
      (drop (call $notify (i32.const 128) (i32.const 17) (i32.const 600) (i32.add (i32.const 11) (local.get $n)))))))

  (func $step
    (local $hx i32) (local $hy i32) (local $i i32) (local $grow i32)
    (global.set $dx (global.get $nx)) (global.set $dy (global.get $ny))
    (local.set $hx (i32.add (call $cx (i32.const 0)) (global.get $dx)))
    (local.set $hy (i32.add (call $cy (i32.const 0)) (global.get $dy)))
    (if (i32.or (i32.or (i32.lt_s (local.get $hx) (i32.const 0)) (i32.lt_s (local.get $hy) (i32.const 0)))
                (i32.or (i32.ge_s (local.get $hx) (i32.const 18)) (i32.ge_s (local.get $hy) (i32.const 18))))
      (then (call $game_over) (return)))
    (local.set $grow (i32.and (i32.eq (local.get $hx) (global.get $fx)) (i32.eq (local.get $hy) (global.get $fy))))
    ;; The tail moves away this step unless we grow, so it doesn't count as a hit.
    (if (call $on_snake (local.get $hx) (local.get $hy) (i32.sub (global.get $len) (i32.xor (local.get $grow) (i32.const 1))))
      (then (call $game_over) (return)))
    (if (local.get $grow) (then (global.set $len (i32.add (global.get $len) (i32.const 1)))))
    (local.set $i (i32.sub (global.get $len) (i32.const 1)))
    (block $d (loop $l
      (br_if $d (i32.eqz (local.get $i)))
      (call $set (local.get $i) (call $cx (i32.sub (local.get $i) (i32.const 1))) (call $cy (i32.sub (local.get $i) (i32.const 1))))
      (local.set $i (i32.sub (local.get $i) (i32.const 1)))
      (br $l)))
    (call $set (i32.const 0) (local.get $hx) (local.get $hy))
    (if (local.get $grow) (then
      (global.set $score (i32.add (global.get $score) (i32.const 1)))
      (if (i32.lt_u (global.get $len) (i32.const 324)) (then (call $place_food))))))

  (func $turn (param $x i32) (param $y i32)
    (if (i32.eq (global.get $state) (i32.const 1)) (then
      (if (i32.or (i32.ne (local.get $x) (i32.sub (i32.const 0) (global.get $dx))) (i32.ne (local.get $y) (i32.sub (i32.const 0) (global.get $dy))))
        (then (global.set $nx (local.get $x)) (global.set $ny (local.get $y)))))))

  (func $cell_rect (param $x i32) (param $y i32) (param $inset f32) (param $r f32) (param $color i32)
    (call $rect
      (f32.add (f32.add (global.get $ox) (f32.mul (f32.convert_i32_u (local.get $x)) (global.get $cell))) (local.get $inset))
      (f32.add (f32.add (global.get $oy) (f32.mul (f32.convert_i32_u (local.get $y)) (global.get $cell))) (local.get $inset))
      (f32.sub (global.get $cell) (f32.mul (local.get $inset) (f32.const 2)))
      (f32.sub (global.get $cell) (f32.mul (local.get $inset) (f32.const 2)))
      (local.get $r) (local.get $color)))

  ;; ---- exports ----
  (func (export "init") (param $w f32) (param $h f32)
    (local $n i32)
    (global.set $w (local.get $w)) (global.set $h (local.get $h))
    (call $layout)
    (local.set $n (call $store_get (i32.const 0) (i32.const 4) (i32.const 512) (i32.const 16)))
    (if (i32.gt_s (local.get $n) (i32.const 0)) (then (global.set $best (call $atoi (i32.const 512) (local.get $n))))))

  (func (export "resize") (param $w f32) (param $h f32)
    (global.set $w (local.get $w)) (global.set $h (local.get $h)) (call $layout))

  (func (export "frame") (param $t f64)
    (local $dark i32) (local $ink i32) (local $sub i32) (local $n i32) (local $i i32) (local $board f32) (local $mid f32)
    (if (global.get $resync) (then (global.set $last (local.get $t)) (global.set $resync (i32.const 0))))
    (if (i32.eq (global.get $state) (i32.const 1)) (then
      (block $d (loop $l
        (br_if $d (f64.lt (f64.sub (local.get $t) (global.get $last)) (f64.const 120)))
        (global.set $last (f64.add (global.get $last) (f64.const 120)))
        (call $step)
        (br_if $d (i32.ne (global.get $state) (i32.const 1)))
        (br $l)))))
    (local.set $dark (call $theme_dark))
    (local.set $ink (select (i32.const 0xFFF2F2F7) (i32.const 0xFF1C1C1E) (local.get $dark)))
    (local.set $sub (select (i32.const 0xFF8E8E93) (i32.const 0xFF6C6C70) (local.get $dark)))
    (call $clear (select (i32.const 0xFF000000) (i32.const 0xFFF2F2F7) (local.get $dark)))
    (local.set $board (f32.mul (global.get $cell) (f32.const 18)))
    (local.set $mid (f32.add (global.get $ox) (f32.div (local.get $board) (f32.const 2))))
    ;; Header: title, then "Score N  ·  Best M"
    (call $text (i32.const 16) (i32.const 5) (global.get $ox) (f32.const 52) (f32.const 30) (local.get $ink) (i32.const 0))
    (memory.copy (i32.const 512) (i32.const 32) (i32.const 6))
    (local.set $n (i32.add (i32.const 6) (call $itoa (global.get $score) (i32.const 518))))
    (memory.copy (i32.add (i32.const 512) (local.get $n)) (i32.const 48) (i32.const 11))
    (local.set $n (i32.add (local.get $n) (i32.const 11)))
    (local.set $n (i32.add (local.get $n) (call $itoa (global.get $best) (i32.add (i32.const 512) (local.get $n)))))
    (call $text (i32.const 512) (local.get $n) (global.get $ox) (f32.const 82) (f32.const 15) (local.get $sub) (i32.const 0))
    ;; Board
    (call $rect (global.get $ox) (global.get $oy) (local.get $board) (local.get $board) (f32.const 14)
      (select (i32.const 0xFF1C1C1E) (i32.const 0xFFFFFFFF) (local.get $dark)))
    (if (i32.ne (global.get $state) (i32.const 0)) (then
      (call $cell_rect (global.get $fx) (global.get $fy) (f32.const 3) (f32.div (global.get $cell) (f32.const 2)) (i32.const 0xFFFF453A))
      (local.set $i (i32.sub (global.get $len) (i32.const 1)))
      (block $d (loop $l
        (call $cell_rect (call $cx (local.get $i)) (call $cy (local.get $i)) (f32.const 1) (f32.div (global.get $cell) (f32.const 3))
          (select (i32.const 0xFF30D158) (i32.const 0xFF7CF29A) (local.get $i)))
        (br_if $d (i32.eqz (local.get $i)))
        (local.set $i (i32.sub (local.get $i) (i32.const 1)))
        (br $l)))))
    ;; Messages
    (if (i32.eqz (global.get $state)) (then
      (call $text (i32.const 64) (i32.const 11) (local.get $mid) (f32.add (global.get $oy) (f32.div (local.get $board) (f32.const 2))) (f32.const 22) (local.get $ink) (i32.const 1))
      (call $text (i32.const 176) (i32.const 27) (local.get $mid) (f32.add (global.get $oy) (f32.add (f32.div (local.get $board) (f32.const 2)) (f32.const 30))) (f32.const 14) (local.get $sub) (i32.const 1))))
    (if (i32.eq (global.get $state) (i32.const 2)) (then
      (memory.copy (i32.const 512) (i32.const 80) (i32.const 13))
      (local.set $n (i32.add (i32.const 13) (call $itoa (global.get $score) (i32.const 525))))
      (call $text (i32.const 512) (local.get $n) (local.get $mid) (f32.add (global.get $oy) (f32.div (local.get $board) (f32.const 2))) (f32.const 22) (local.get $ink) (i32.const 1))
      (call $text (i32.const 64) (i32.const 11) (local.get $mid) (f32.add (global.get $oy) (f32.add (f32.div (local.get $board) (f32.const 2)) (f32.const 30))) (f32.const 14) (local.get $sub) (i32.const 1))))
    (if (i32.eq (global.get $state) (i32.const 3)) (then
      (call $text (i32.const 96) (i32.const 23) (local.get $mid) (f32.add (global.get $oy) (f32.div (local.get $board) (f32.const 2))) (f32.const 20) (local.get $ink) (i32.const 1)))))

  ;; kind: 0 down, 1 move, 2 up (a tap starts, restarts or resumes)
  (func (export "on_touch") (param $kind i32) (param $x f32) (param $y f32) (param $id i32)
    (if (i32.eq (local.get $kind) (i32.const 2)) (then
      (if (i32.eq (global.get $state) (i32.const 3)) (then (global.set $state (i32.const 1)) (global.set $resync (i32.const 1)) (return)))
      (if (i32.ne (global.get $state) (i32.const 1)) (then (call $start))))))

  ;; kind 1: swipe by (dx, dy)
  (func (export "on_gesture") (param $kind i32) (param $dx f32) (param $dy f32)
    (if (i32.ne (local.get $kind) (i32.const 1)) (then (return)))
    (if (f32.gt (f32.abs (local.get $dx)) (f32.abs (local.get $dy)))
      (then (call $turn (select (i32.const 1) (i32.const -1) (f32.gt (local.get $dx) (f32.const 0))) (i32.const 0)))
      (else (call $turn (i32.const 0) (select (i32.const 1) (i32.const -1) (f32.gt (local.get $dy) (f32.const 0)))))))

  ;; 37 left, 38 up, 39 right, 40 down, 13 enter / 32 space start
  (func (export "on_key") (param $code i32) (param $down i32)
    (if (i32.eqz (local.get $down)) (then (return)))
    (if (i32.eq (local.get $code) (i32.const 37)) (then (call $turn (i32.const -1) (i32.const 0))))
    (if (i32.eq (local.get $code) (i32.const 38)) (then (call $turn (i32.const 0) (i32.const -1))))
    (if (i32.eq (local.get $code) (i32.const 39)) (then (call $turn (i32.const 1) (i32.const 0))))
    (if (i32.eq (local.get $code) (i32.const 40)) (then (call $turn (i32.const 0) (i32.const 1))))
    (if (i32.or (i32.eq (local.get $code) (i32.const 13)) (i32.eq (local.get $code) (i32.const 32))) (then
      (if (i32.eq (global.get $state) (i32.const 3)) (then (global.set $state (i32.const 1)) (global.set $resync (i32.const 1)) (return)))
      (if (i32.ne (global.get $state) (i32.const 1)) (then (call $start))))))

  (func (export "on_pause")
    (if (i32.eq (global.get $state) (i32.const 1)) (then (global.set $state (i32.const 3)))))
  (func (export "on_resume") (global.set $resync (i32.const 1)))
  ;; Back while playing pauses (handled = 1); otherwise BlazeOS goes home.
  (func (export "on_back") (result i32)
    (if (i32.eq (global.get $state) (i32.const 1)) (then (global.set $state (i32.const 3)) (return (i32.const 1))))
    (i32.const 0))
)
