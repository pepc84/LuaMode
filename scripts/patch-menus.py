import re, sys
p = 'src/java/processing/mode/lua/LuaEditor.java'
s = open(p).read()

m = re.search(r'public void handleRun\(([^)]*)\)', s)
params = m.group(1).strip() if m else ''
if not params:
    run_call = 'handleRun()'
elif re.fullmatch(r'int \w+', params):
    run_call = 'handleRun(0)'
elif re.fullmatch(r'boolean \w+,\s*Runnable \w+,\s*Runnable \w+', params):
    run_call = 'handleRun(false, null, null)'
else:
    sys.exit(f"unexpected handleRun({params}), paste it to me")

for imp in ['processing.app.Language', 'processing.app.Platform', 'processing.app.ui.Toolkit']:
    if f'import {imp};' not in s:
        s = s.replace('import processing.app.Base;', f'import processing.app.Base;\nimport {imp};', 1)

s = re.sub(r'\n\s*addExportMenuItems\(\);', '', s, count=1)
s = re.sub(r'\n[ \t]*(?:private )?void addExportMenuItems\(\) \{.*?\n    \}\n', '\n', s, count=1, flags=re.S)

stub = r'[ \t]*@Override\s+public\s+(?:javax\.swing\.)?JMenu\s+build{}Menu\(\)\s*\{{\s*return new (?:javax\.swing\.)?JMenu\(\);\s*\}}\n'
menus = f'''    // Real menus: Editor adds the standard items (Import Library etc.) through
    // the protected buildXMenu(JMenuItem[]) overloads. An empty JMenu here is
    // what caused the removeImportMenu NPE.
    @Override
    public JMenu buildFileMenu() {{
        JMenuItem roblox = new JMenuItem("Export for Roblox...");
        roblox.addActionListener(this::handleRobloxExport);
        JMenuItem love2d = new JMenuItem("Export for LOVE2D...");
        love2d.addActionListener(this::handleLove2dExport);
        return buildFileMenu(new JMenuItem[] {{ roblox, love2d }});
    }}

    @Override
    public JMenu buildSketchMenu() {{
        JMenuItem run = Toolkit.newJMenuItem(Language.text("menu.sketch.run"), 'R');
        run.addActionListener(e -> {run_call});
        JMenuItem stop = new JMenuItem(Language.text("menu.sketch.stop"));
        stop.addActionListener(e -> handleStop());
        return buildSketchMenu(new JMenuItem[] {{ run, stop }});
    }}

    @Override
    public JMenu buildHelpMenu() {{
        JMenu help = new JMenu(Language.text("menu.help"));
        JMenuItem site = new JMenuItem("Lua Mode on GitHub");
        site.addActionListener(e -> Platform.openURL("https://github.com/processing-cpp/processing-lua"));
        help.add(site);
        JMenuItem ref = new JMenuItem("Lua 5.4 Reference Manual");
        ref.addActionListener(e -> Platform.openURL("https://www.lua.org/manual/5.4/"));
        help.add(ref);
        return help;
    }}
'''
found = 0
for name in ['File', 'Sketch', 'Help']:
    s, n = re.subn(stub.format(name), (menus if found == 0 else ''), s, count=1)
    found += n
if found != 3:
    sys.exit(f"found {found}/3 empty menu stubs, paste the buildXMenu lines to me")

for a, b in [('"Warning — "', '"Warning: "'),
             ('"Export for Roblox — save as LocalScript"', '"Export for Roblox (save as LocalScript)"'),
             ('"Export for LOVE2D — choose output folder"', '"Export for LOVE2D (choose output folder)"'),
             ('"/  — run with: love "', '"/. Run it with: love "')]:
    s = s.replace(a, b)
open(p, 'w').write(s)

# linter messages: "X instead of Y — reason" -> "X instead of Y. Reason"
lp = 'src/java/processing/mode/lua/LuaLinter.java'
l = open(lp).read()
l = re.sub(r'(instead of [^"]*?) — (\w)', lambda m: m.group(1) + '. ' + m.group(2).upper(), l)
l = l.replace('automatically — you', 'automatically, you')
open(lp, 'w').write(l)

print("patched, Run item calls", run_call)
