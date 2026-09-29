#!/usr/bin/env bash
# Builds lib/<platform>/<java-tree-sitter native lib> for a Processing mode
# (LuaMode, RustMode, SchemeMode, ...). Drop it in <Mode>/native/.
#
# The Java side of java-tree-sitter comes from the maven jar pinned in
# build.gradle, so the native side has to be built from the SAME tag or
# JNI_OnLoad goes looking for classes that don't exist and crashes the IDE.
#
# Only one grammar is compiled in. The Language enum still asks every
# other language for its version/fields/symbols at class init, so we patch
# those three natives to return 0 for a null language instead of segfaulting.
#
# tree-sitter core is the copy pinned by java-tree-sitter, compiled in
# statically with hidden visibility, so it can't clash with the system
# libtree-sitter or with CppMode/RustMode's copies.
#
# Usage:
#   native/build.sh [grammar]                        default grammar: lua
#   LIB_PLATFORM=linux-x86-64 native/build.sh rust   override lib/<dir> name
#   ARCH=x86_64 native/build.sh                      macOS: Intel build on Apple Silicon
#   TARGET_OS=windows CC=x86_64-w64-mingw32-gcc CXX=x86_64-w64-mingw32-g++ \
#     JNI_MD_DIR=/path/to/win32 native/build.sh      cross-compile for Windows
#
# Runs on Linux, macOS, and Windows (Git Bash / MSYS2 with MinGW g++).
# Needs bash, git, perl, a C/C++ compiler and a JDK. Work dir: native/.build,
# safe to delete.

set -euo pipefail

HERE=$(cd "$(dirname "$0")" && pwd)
ROOT=$(dirname "$HERE")
WORK=${WORK:-$HERE/.build}
JTS_REPO=https://github.com/seart-group/java-tree-sitter
GRAMMAR=${1:-lua}
GRAMMAR_REPO=tree-sitter-$GRAMMAR
GRAMMAR_MACRO=TS_LANGUAGE_$(echo "$GRAMMAR" | tr 'a-z-' 'A-Z_')
GRAMMAR_JNI=Java_ch_usi_si_seart_treesitter_Language_$(echo "$GRAMMAR" | perl -pe 's/-(.)/\U$1/g')

# Keep the native build locked to whatever build.gradle embeds.
JTS_VERSION=$(sed -nE 's/.*ch\.usi\.si\.seart:java-tree-sitter:([0-9.]+).*/\1/p' "$ROOT/build.gradle" | head -1)
[ -n "$JTS_VERSION" ] || { echo "build.gradle doesn't depend on ch.usi.si.seart:java-tree-sitter" >&2; exit 1; }
JTS_TAG="v$JTS_VERSION"

# ── target ─────────────────────────────────────────────────────────────────
if [ -z "${TARGET_OS:-}" ]; then
  case "$(uname -s)" in
    Linux)                TARGET_OS=linux ;;
    Darwin)               TARGET_OS=macos ;;
    MINGW*|MSYS*|CYGWIN*) TARGET_OS=windows ;;
    *) echo "unsupported OS: $(uname -s)" >&2; exit 1 ;;
  esac
fi
ARCH=${ARCH:-$(uname -m)}
case "$ARCH" in
  x86_64|amd64)  ARCH=x86_64 ;;
  arm64|aarch64) ARCH=aarch64 ;;
  *) echo "unsupported arch: $ARCH" >&2; exit 1 ;;
esac
PLATFORM=${LIB_PLATFORM:-$TARGET_OS-$ARCH}

# File names match System.mapLibraryName("java-tree-sitter") on each OS.
case $TARGET_OS in
  linux)   LIB_NAME=libjava-tree-sitter.so;    JNI_OS=linux  ;;
  macos)   LIB_NAME=libjava-tree-sitter.dylib; JNI_OS=darwin ;;
  windows) LIB_NAME=java-tree-sitter.dll;      JNI_OS=win32  ;;
esac

if [ "$TARGET_OS" = macos ]; then CC=${CC:-clang}; CXX=${CXX:-clang++}; else CC=${CC:-gcc}; CXX=${CXX:-g++}; fi

if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME=$(dirname "$(dirname "$(perl -MCwd -e 'print Cwd::abs_path shift' "$(command -v java)")")")
fi
[ -f "$JAVA_HOME/include/jni.h" ] || { echo "no jni.h under $JAVA_HOME (need a JDK)" >&2; exit 1; }
JNI_MD_DIR=${JNI_MD_DIR:-$JAVA_HOME/include/$JNI_OS}

echo "java-tree-sitter $JTS_TAG + $GRAMMAR_REPO -> lib/$PLATFORM/$LIB_NAME"

# ── sources ────────────────────────────────────────────────────────────────
JTS=$WORK/jts-$JTS_VERSION
if [ ! -d "$JTS/.git" ]; then
  rm -rf "$JTS"
  git -c advice.detachedHead=false clone -q --depth=1 --branch "$JTS_TAG" "$JTS_REPO" "$JTS"
fi
git -C "$JTS" submodule update -q --init --depth=1 tree-sitter "$GRAMMAR_REPO"
grep -q "$GRAMMAR_MACRO" "$JTS/lib/ch_usi_si_seart_treesitter_Language.cc" || {
  echo "java-tree-sitter $JTS_TAG has no $GRAMMAR_MACRO" >&2; exit 1; }

# ── null guards so the Language enum can init invalid languages safely ────
LANG_CC=$JTS/lib/ch_usi_si_seart_treesitter_Language.cc
for fn in ts_language_version ts_language_symbol_count ts_language_field_count; do
  FN=$fn perl -pi -e 's/return \(jint\)$ENV{FN}\(\(const TSLanguage \*\)id\);/return id ? (jint)$ENV{FN}((const TSLanguage *)id) : 0;/' "$LANG_CC"
  grep -q "return id ? (jint)$fn" "$LANG_CC" || { echo "null-guard patch failed for $fn" >&2; exit 1; }
done

# ── compile ────────────────────────────────────────────────────────────────
OBJ=$WORK/obj-$PLATFORM-$GRAMMAR
rm -rf "$OBJ" && mkdir -p "$OBJ"

CFLAGS=(-O2 -fvisibility=hidden)
case $TARGET_OS in
  linux)
    CFLAGS+=(-fPIC)
    LDFLAGS=(-shared -Wl,-z,defs) ;;
  macos)
    if [ "$ARCH" = aarch64 ]; then MARCH=arm64; else MARCH=x86_64; fi
    CFLAGS+=(-fPIC -arch "$MARCH" -mmacosx-version-min=11.0)
    LDFLAGS=(-dynamiclib) ;;   # undefined symbols are already a link error on macOS
  windows)
    # static runtime: the DLL has to load on machines without MinGW installed
    LDFLAGS=(-shared -static-libgcc -static-libstdc++ -Wl,--no-undefined -s) ;;
esac

TS=$JTS/tree-sitter/lib
"$CC" "${CFLAGS[@]}" -I "$TS/include" -I "$TS/src" -c "$TS/src/lib.c" -o "$OBJ/tree-sitter.o"

case $GRAMMAR in   # same layout rules as upstream build.py
  markdown)   G_SRC=$JTS/$GRAMMAR_REPO/$GRAMMAR_REPO/src ;;
  ocaml)      G_SRC=$JTS/$GRAMMAR_REPO/ocaml/src ;;
  tsx)        G_SRC=$JTS/$GRAMMAR_REPO/tsx/src ;;
  typescript) G_SRC=$JTS/$GRAMMAR_REPO/typescript/src ;;
  *)          G_SRC=$JTS/$GRAMMAR_REPO/src ;;
esac
"$CC" "${CFLAGS[@]}" -I "$G_SRC" -c "$G_SRC/parser.c" -o "$OBJ/grammar-parser.o"
if [ -f "$G_SRC/scanner.c" ]; then
  "$CC" "${CFLAGS[@]}" -I "$G_SRC" -c "$G_SRC/scanner.c" -o "$OBJ/grammar-scanner.o"
elif [ -f "$G_SRC/scanner.cc" ]; then
  "$CXX" "${CFLAGS[@]}" -I "$G_SRC" -c "$G_SRC/scanner.cc" -o "$OBJ/grammar-scanner.o"
fi

OUT=$OBJ/$LIB_NAME
# The JDK's own headers go first: java-tree-sitter ships a Linux jni_md.h in
# its include/, and if that one wins, JNIEXPORT never becomes
# __declspec(dllexport) on Windows and the DLL exports no JNI functions.
"$CXX" "${CFLAGS[@]}" "${LDFLAGS[@]}" -D"$GRAMMAR_MACRO" \
  -I "$JAVA_HOME/include" -I "$JNI_MD_DIR" \
  -I "$JTS/include" -I "$TS/include" \
  "$JTS"/lib/*.cc "$OBJ"/*.o \
  -o "$OUT"

case $TARGET_OS in
  linux) strip --strip-unneeded "$OUT" ;;
  macos) strip -x "$OUT" ;;
esac

# ── sanity checks ──────────────────────────────────────────────────────────
# (capture first: grep -q exits early, and with pipefail the SIGPIPE it
# causes upstream would fail the check even when the symbol is there)
case $TARGET_OS in
  linux)
    SYMS=$(nm -D --defined-only "$OUT")
    DEPS=$(ldd "$OUT" 2>/dev/null || true) ;;
  macos)
    SYMS=$(nm -gU "$OUT")
    DEPS=$(otool -L "$OUT" | tail -n +2) ;;   # line 1 is the file itself
  windows)
    OBJDUMP=${OBJDUMP:-objdump}
    command -v "$OBJDUMP" >/dev/null || OBJDUMP=x86_64-w64-mingw32-objdump
    SYMS=$("$OBJDUMP" -p "$OUT" | sed -n '/\[Ordinal\/Name Pointer\] Table/,/^$/p')
    DEPS=$("$OBJDUMP" -p "$OUT" | grep 'DLL Name' || true) ;;
esac
grep -qE "[] _]JNI_OnLoad\$"   <<<"$SYMS" || { echo "JNI_OnLoad not exported" >&2; exit 1; }
grep -qE "[] _]$GRAMMAR_JNI\$" <<<"$SYMS" || { echo "$GRAMMAR_JNI not exported" >&2; exit 1; }
if grep -qE "[] _]ts_[a-z_]+\$" <<<"$SYMS"; then
  echo "tree-sitter symbols leaked into the export table" >&2; exit 1
fi
# match a real libtree-sitter (not our own libjava-tree-sitter)
if grep -qiE "(^|[/[:space:]])libtree-sitter[.-]" <<<"$DEPS"; then
  echo "linked against a system libtree-sitter, expected static" >&2; exit 1
fi
if [ "$TARGET_OS" = windows ] && grep -qiE "libstdc\+\+|libgcc_s|libwinpthread" <<<"$DEPS"; then
  echo "DLL depends on the MinGW runtime, expected it linked statically" >&2; exit 1
fi

# ── install ────────────────────────────────────────────────────────────────
DEST=$ROOT/lib/$PLATFORM
mkdir -p "$DEST"
cp "$OUT" "$DEST/$LIB_NAME"
chmod 755 "$DEST/$LIB_NAME"
rm -f "$DEST/libtree-sitter-$GRAMMAR".* "$DEST/tree-sitter-$GRAMMAR.dll"   # grammar lives inside our lib now

ls -la "$DEST/$LIB_NAME"
echo "done. now: SKIP_RUNNER=1 gradle modeJar"
