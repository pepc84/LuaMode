# Native grammar libraries

Place the tree-sitter-lua grammar shared library for each platform here:

| Platform           | File                         |
|--------------------|------------------------------|
| linux-x86_64       | libtree-sitter-lua.so        |
| linux-aarch64      | libtree-sitter-lua.so        |
| macos-x86_64       | libtree-sitter-lua.dylib     |
| macos-aarch64      | libtree-sitter-lua.dylib     |
| windows-x86_64     | tree-sitter-lua.dll          |

Build from: https://github.com/tree-sitter-grammars/tree-sitter-lua
  git clone https://github.com/tree-sitter-grammars/tree-sitter-lua
  cd tree-sitter-lua
  cc -shared -fPIC -O2 src/parser.c src/scanner.c -o libtree-sitter-lua.so
