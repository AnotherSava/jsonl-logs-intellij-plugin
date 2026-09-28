"""Capture the documentation screenshots from a sandbox IDE driven over the Remote Robot server.

    python docs/screenshots/capture/capture.py launch            # start the sandbox and wait for it
    python docs/screenshots/capture/capture.py <shot> [...]      # stage and capture one or more shots
    python docs/screenshots/capture/capture.py preview <shot>    # stage a screen shot and paint it, no capture
    python docs/screenshots/capture/capture.py stop              # close the sandbox

The sandbox is the `runIdeForScreenshots` Gradle task: the IDE release the published shots show,
at a device-pixel ratio of 1.5, with the robot server on port 8582. Every shot is staged through
inputs the plugin already reads (its settings, the per-file defaults it stores in
PropertiesComponent, the fixture log it opens, the toolbar buttons it draws) and painted from
inside the IDE, which touches neither the screen nor the pointer. Three shots read the pixels off
the screen instead, because Windows draws what they show: the gear menu's popup border and shadow,
and the two Settings dialogs' whole window frame. Those take the machine over for a moment: they
raise a window, and the dialogs' shutter parks the pointer and presses Alt. `preview` stages one of
the dialogs and paints it in-process, so the staging can be checked before the screen is read.
"""

import datetime
import functools
import json
import os
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request

from PIL import Image, ImageGrab

SKILL_SCRIPTS = os.path.join(os.path.expanduser("~"), ".claude", "skills", "docs-relevance", "scripts")
sys.path.insert(0, SKILL_SCRIPTS)
import outline  # noqa: E402 — the skill's outline finder, shared with every project's captures

HAIRLINE = os.path.join(SKILL_SCRIPTS, "hairline.py")
SHUTTER = os.path.join(SKILL_SCRIPTS, "window-shot.ps1")
ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__)))))
SHOTS_DIR = os.path.join(ROOT, "docs", "screenshots")
FIXTURES_DIR = os.path.join(SHOTS_DIR, "capture", "fixtures")
WORK = os.path.join(ROOT, "tmp", "screenshot-sandbox")
PROJECT = os.path.join(WORK, "project")
PORT = 8582
SCALE = 1.5
# The pixel density every published frame carries: the device-pixel ratio's share of 96 dpi.
DPI = round(96 * SCALE)
# Keep the subprocesses the capture starts from flashing a console window on the user's desktop.
NO_WINDOW = getattr(subprocess, "CREATE_NO_WINDOW", 0)
# How long a robot call may take, in seconds: a paint of the whole editor at 1.5 is the slowest.
ROBOT_TIMEOUT = 120
# How far before the capture a relative-time fixture's last entry lands, in seconds.
RELATIVE_LEAD = 20.0

# Everything a staging script needs, evaluated at the top of each call: Rhino keeps no state
# between calls except the `global` map, and plugin classes are invisible to it by name.
PRELUDE = r"""
var CL = com.intellij.ide.plugins.PluginManagerCore.getPlugin(com.intellij.openapi.extensions.PluginId.getId("com.olegs.jsonl")).getPluginClassLoader();
function K(n) { return java.lang.Class.forName(n, true, CL); }
function E(cls, name) { return java.lang.Enum.valueOf(K("com.olegs.jsonl." + cls), name); }
function project() { return com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects()[0]; }
function frame() { return com.intellij.openapi.wm.WindowManager.getInstance().getFrame(project()); }
function fem() { return com.intellij.openapi.fileEditor.FileEditorManager.getInstance(project()); }
function jsonlEditor() {
    var eds = fem().getSelectedEditors();
    for (var i = 0; i < eds.length; i++) if (eds[i].getClass().getName() == "com.olegs.jsonl.JsonlEditor") return eds[i];
    throw "no JsonlEditor is selected";
}
// The editor island, the rounded panel the JSONL editor sits in: `EditorsSplitters` draws it.
function island() {
    var c = jsonlEditor().getComponent();
    while (c != null && String(c.getClass().getName()) != "com.intellij.openapi.fileEditor.impl.EditorsSplitters") c = c.getParent();
    if (c == null) throw "no EditorsSplitters above the JSONL editor";
    return c;
}
function settings() { return K("com.olegs.jsonl.JsonlSettings").getField("Companion").get(null).getInstance(); }
function descendants(root, pred) {
    var out = [], stack = [root];
    while (stack.length) {
        var c = stack.pop();
        if (pred(c)) out.push(c);
        if (c instanceof java.awt.Container) { var kids = c.getComponents(); for (var i = kids.length - 1; i >= 0; i--) stack.push(kids[i]); }
    }
    return out;
}
function textOf(c) {
    try { if (c.getText) return String(c.getText()); } catch (e) {}
    return "";
}
// A private field, looked up through the class hierarchy.
function field(obj, name) {
    for (var c = obj.getClass(); c != null; c = c.getSuperclass()) {
        try { var f = c.getDeclaredField(name); f.setAccessible(true); return f.get(obj); } catch (e) {}
    }
    throw "no field " + name + " on " + obj.getClass().getName();
}
// The open Settings dialog's wrapper, or null.
function settingsDialog() {
    var wins = java.awt.Window.getWindows();
    for (var i = 0; i < wins.length; i++) {
        if (!wins[i].isShowing()) continue;
        var dw = com.intellij.openapi.ui.DialogWrapper.findInstance(wins[i]);
        if (dw instanceof com.intellij.openapi.options.newEditor.SettingsDialog) return dw;
    }
    return null;
}
function writeJson(path, obj) {
    var w = new java.io.OutputStreamWriter(new java.io.FileOutputStream(path), "UTF-8");
    try { w.write(JSON.stringify(obj)); } finally { w.close(); }
}
// Lay out what a picture would show: every component under `root`, with its bounds relative to it.
function layout(root) {
    return descendants(root, function (c) { return c.isShowing(); }).map(function (c) {
        var p = javax.swing.SwingUtilities.convertPoint(c.getParent() || c, c.getX(), c.getY(), root);
        if (c === root) p = new java.awt.Point(0, 0);
        return {cls: String(c.getClass().getName()), text: textOf(c), x: p.x, y: p.y, w: c.getWidth(), h: c.getHeight()};
    });
}
// Paint `c` the way the screen does, at the sandbox's device-pixel ratio, into an opaque image:
// an ARGB target or a non-rectangular clip would silently lose subpixel text.
function paintPng(c, path) {
    var S = __SCALE__, w = c.getWidth(), h = c.getHeight();
    // Floor, not round: a logical size that scales to a half pixel leaves its last row half painted,
    // black in an image that starts black, and a crop to the outline would take that row for subject.
    var img = new java.awt.image.BufferedImage(Math.floor(w * S), Math.floor(h * S), java.awt.image.BufferedImage.TYPE_INT_RGB);
    var g = img.createGraphics();
    try { g.scale(S, S); g.setClip(0, 0, w, h); c.paint(g); } finally { g.dispose(); }
    var wr = javax.imageio.ImageIO.getImageWritersByFormatName("png").next();
    var meta = wr.getDefaultImageMetadata(javax.imageio.ImageTypeSpecifier.createFromRenderedImage(img), null);
    var tree = new javax.imageio.metadata.IIOMetadataNode("javax_imageio_png_1.0"), phys = new javax.imageio.metadata.IIOMetadataNode("pHYs");
    phys.setAttribute("pixelsPerUnitXAxis", "__PPM__"); phys.setAttribute("pixelsPerUnitYAxis", "__PPM__"); phys.setAttribute("unitSpecifier", "meter");
    tree.appendChild(phys); meta.mergeTree("javax_imageio_png_1.0", tree);
    var out = javax.imageio.ImageIO.createImageOutputStream(new java.io.File(path));
    try { wr.setOutput(out); wr.write(null, new javax.imageio.IIOImage(img, null, meta), null); } finally { out.close(); wr.dispose(); }
}
""".replace("__SCALE__", str(SCALE)).replace("__PPM__", str(round(DPI / 0.0254)))


class RobotError(RuntimeError):
    pass


def _post(path: str, body: dict) -> dict:
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}{path}", data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=ROBOT_TIMEOUT) as resp:
        reply = json.loads(resp.read().decode("utf-8"))
    if reply.get("status") != "SUCCESS":
        raise RobotError(f"{path}: {reply.get('message') or reply}")
    return reply


def _js(script: str, edt: bool = True) -> None:
    """Run `script` inside the IDE. Wrapped so a thrown JS error comes back as its message."""
    _post("/js/execute", {"script": PRELUDE + script, "runInEdt": edt})


def _js_json(script: str) -> object:
    """Run `script` on the EDT, which must call `writeJson(OUT, value)`, and return that value."""
    out = os.path.join(WORK, "robot-out.json")
    if os.path.exists(out):
        os.remove(out)
    _js(f"var OUT = {json.dumps(out)};\n" + script)
    with open(out, encoding="utf-8") as fh:
        return json.load(fh)


def _alive() -> bool:
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{PORT}/hello", timeout=3) as resp:
            return resp.status == 200
    except (urllib.error.URLError, OSError):
        return False


def _wait(what: str, check, timeout: float, every: float = 0.5) -> None:
    end = time.time() + timeout
    while time.time() < end:
        if check():
            return
        time.sleep(every)
    raise TimeoutError(f"timed out waiting for {what}")


def _powershell(script: str, *args: str) -> str:
    """Run a PowerShell script without a console window and return what it printed; a failure raises with its error text."""
    run = subprocess.run(["powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", script, *args], capture_output=True, text=True, creationflags=NO_WINDOW)
    if run.returncode != 0:
        raise RuntimeError(f"{os.path.basename(script)} failed:\n{run.stderr.strip()}")
    return run.stdout


def _launch() -> None:
    if _alive():
        print("sandbox already running")
        return
    os.makedirs(WORK, exist_ok=True)
    if os.path.exists(PROJECT):
        shutil.rmtree(PROJECT)
    os.makedirs(PROJECT)
    log_path = os.path.join(WORK, "sandbox.log")
    log = open(log_path, "w", encoding="utf-8")
    # gradlew.bat rather than `bash gradlew` on Windows: CreateProcess searches System32 before
    # PATH, so a bare "bash" there is WSL's, which runs the whole build and IDE inside Linux.
    gradlew = [os.path.join(ROOT, "gradlew.bat")] if os.name == "nt" else ["./gradlew"]
    proc = subprocess.Popen(gradlew + ["runIdeForScreenshots", "--args=" + PROJECT], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, creationflags=NO_WINDOW)

    def server_up() -> bool:
        if proc.poll() is not None:
            with open(log_path, encoding="utf-8", errors="replace") as fh:
                tail = fh.read()[-2000:]
            raise RuntimeError(f"the sandbox exited with {proc.returncode} before its robot server answered:\n{tail}")
        return _alive()

    _wait("the robot server", server_up, timeout=600, every=2)
    _wait("the project to open", lambda: _project_open(), timeout=180, every=2)
    time.sleep(3)
    # A fresh 2026.1 config opens "Meet the Islands Theme"; press its own Skip link. The published
    # shots use the Dark editor scheme, which the Islands theme does not select by default, and
    # hide the editor tabs, so the JSONL editor's own corners are the editor island's rounded ones.
    _js("""
var wins = java.awt.Window.getWindows();
for (var i = 0; i < wins.length; i++) {
    if (!wins[i].isShowing() || !(wins[i] instanceof java.awt.Dialog)) continue;
    var skip = descendants(wins[i], function (c) { return c.isShowing() && textOf(c) == "Skip" && c.doClick; });
    if (skip.length) skip[0].doClick();
}
var ecm = com.intellij.openapi.editor.colors.EditorColorsManager.getInstance();
ecm.setGlobalScheme(ecm.getScheme("Dark"));
var ui = com.intellij.ide.ui.UISettings.getInstance();
ui.setEditorTabPlacement(0);
ui.fireUISettingsChanged();
""")
    print("sandbox ready")


def _project_open() -> bool:
    try:
        _js("if (com.intellij.openapi.project.ProjectManager.getInstance().getOpenProjects().length == 0) throw 'no project';", edt=False)
        return True
    except (RobotError, urllib.error.URLError):
        return False


def _stop() -> None:
    if _alive():
        try:
            # A modal dialog holds the exit back until it closes; a Settings dialog is the one a shot opens.
            _js("var dw = settingsDialog(); if (dw != null) dw.doCancelAction();")
            _js("com.intellij.openapi.application.ApplicationManager.getApplication().exit(true, true, false);", edt=False)
        except (RobotError, urllib.error.URLError, ConnectionError):
            pass
        _wait("the sandbox to exit", lambda: not _alive(), timeout=60, every=1)
    print("sandbox stopped")


def _shifted_to_now(src: str, dest: str) -> None:
    """Copy the fixture log with every timestamp moved by one offset, so its last entry is RELATIVE_LEAD
    seconds before now: the toolbar's relative times then read as a session that just happened."""
    with open(src, encoding="utf-8") as fh:
        entries = [json.loads(line) for line in fh if line.strip()]
    parse = lambda v: datetime.datetime.strptime(v, "%Y-%m-%dT%H:%M:%S.%fZ")
    delta = datetime.datetime.now(datetime.UTC).replace(tzinfo=None) - datetime.timedelta(seconds=RELATIVE_LEAD) - parse(entries[-1]["timestamp"])
    with open(dest, "w", encoding="utf-8", newline="\n") as fh:
        for e in entries:
            e["timestamp"] = (parse(e["timestamp"]) + delta).strftime("%Y-%m-%dT%H:%M:%S.%fZ")
            fh.write(json.dumps(e, separators=(",", ":"), ensure_ascii=False) + "\n")


def _open_fixture(name: str, props: dict, config: dict, to_now: bool = False) -> None:
    """Close every editor, apply the plugin's stored defaults and settings, then open `name`.

    The per-file defaults the plugin reads when it first opens a file live in PropertiesComponent,
    which is exactly what real use leaves behind; setting them before the open means the editor is
    constructed in the state the shot needs rather than switched into it. `to_now` moves the log's
    timestamps to end just before the capture, for a shot whose toolbar shows relative times.
    """
    # A fresh name every time: the IDE restores a reopened file's own editor state over the
    # defaults, so reusing one name would carry the previous shot's panes into this one.
    stem, ext = os.path.splitext(name)
    dest = os.path.join(PROJECT, f"{stem}-{time.time_ns()}{ext}")
    if to_now:
        _shifted_to_now(os.path.join(FIXTURES_DIR, name), dest)
    else:
        shutil.copyfile(os.path.join(FIXTURES_DIR, name), dest)
    _js("com.intellij.openapi.vfs.LocalFileSystem.getInstance().refreshAndFindFileByPath(%s);" % json.dumps(dest.replace(os.sep, "/")), edt=False)
    setters = "".join(f"s.set{k[0].upper()}{k[1:]}({v});\n" for k, v in config.items())
    _js("""
var open = fem().getOpenFiles();
for (var i = 0; i < open.length; i++) fem().closeFile(open[i]);
var props = com.intellij.ide.util.PropertiesComponent.getInstance();
var P = %s;
for (var k in P) props.setValue(k, P[k]);
var s = settings().getState();
%s
settings().notifyChanged();
var vf = com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByPath(%s);
fem().openFile(vf, true);
""" % (json.dumps(props), setters, json.dumps(dest.replace(os.sep, "/"))))
    _wait("the JSONL editor", lambda: _has_editor(), timeout=30)
    time.sleep(1.5)


def _has_editor() -> bool:
    try:
        _js("jsonlEditor();")
        return True
    except RobotError:
        return False


STARTUP_FIXTURE = "dashboard-startup.jsonl"
HERO_FIXTURE = "dashboard-session.jsonl"
# The plugin's defaults, spelled out so a shot never inherits a toggle an earlier shot flipped.
DEFAULT_CONFIG = {
    "stripCommonPrefix": "true", "colourSeverity": "true", "highlightFieldNames": "true", "boldMessage": "true",
    "italicTarget": "true", "dimTimestampAndEquals": "true", "prettifyValues": "true",
    "alignment": 'E("Alignment", "TARGETS")', "timestampDecimals": "0",
    "scrollToEndOnOpen": "false", "autoResizeInspect": "false", "softWrapInspect": "true", "softWrapFormatted": "true",
}


def _panes(left: str, right: str, overlay: tuple[int, int, str] | None = None, time_display: str | None = None) -> dict:
    """The plugin's stored per-file defaults: the two panes, and optionally the Inspect overlay's
    width, height and corner and the toolbar's time display. Each key the plugin reads is named here once."""
    props = {"com.olegs.jsonl.leftPane": left, "com.olegs.jsonl.rightPane": right}
    if overlay:
        width, height, corner = overlay
        props |= {"com.olegs.jsonl.inspectOverlay.width": str(width), "com.olegs.jsonl.inspectOverlay.height": str(height), "com.olegs.jsonl.inspectOverlay.corner": corner}
    if time_display:
        props["com.olegs.jsonl.timeDisplay"] = time_display
    return props


def _size_editor(width: int, height: int) -> None:
    """Resize the sandbox frame so the JSONL editor component is `width` x `height` logical pixels."""
    for _ in range(3):
        cur = _js_json("var c = jsonlEditor().getComponent(); writeJson(OUT, {w: c.getWidth(), h: c.getHeight()});")
        dw, dh = width - cur["w"], height - cur["h"]
        if dw == 0 and dh == 0:
            return
        _js(f"""var f = frame(); f.setExtendedState(java.awt.Frame.NORMAL);
var b = f.getBounds(); f.setBounds(40, 40, b.width + ({dw}), b.height + ({dh})); f.validate();""")
        time.sleep(1.0)
    cur = _js_json("var c = jsonlEditor().getComponent(); writeJson(OUT, {w: c.getWidth(), h: c.getHeight()});")
    if (cur["w"], cur["h"]) != (width, height):
        raise RuntimeError(f"editor is {cur['w']}x{cur['h']}, wanted {width}x{height}: the screen may be too small for this frame")


def _formatted_editor_expr() -> str:
    """JS expression for the Editor behind the left pane, which every shot here puts Formatted or Raw in."""
    return "descendants(jsonlEditor().getPreferredFocusedComponent(), function (c) { return c instanceof com.intellij.openapi.editor.impl.EditorComponentImpl; })[0].getEditor()"


def _caret_to(line: int) -> None:
    _js(f"""var ed = {_formatted_editor_expr()};
ed.getCaretModel().moveToLogicalPosition(new com.intellij.openapi.editor.LogicalPosition({line - 1}, 0));
ed.getScrollingModel().scrollVertically(0);""")
    time.sleep(0.8)


def _paint_editor(path: str) -> list:
    """Paint the whole JSONL editor to `path` and return the layout of its components."""
    return _js_json(f"var c = jsonlEditor().getComponent(); paintPng(c, {json.dumps(path)}); writeJson(OUT, layout(c));")


def _paint_island(path: str) -> int:
    """Paint the editor island — the rounded panel the JSONL editor sits in — to `path`, corners and all.

    The island's rounded outline and its one-pixel border are drawn by `EditorsSplitters`, not by
    the editor, so a shot that shows the island's corners paints that component. Returns how far
    below the island's top the JSONL editor starts, in logical pixels, for crops measured against it.
    """
    return _js_json(f"""var c = island();
paintPng(c, {json.dumps(path)});
writeJson(OUT, javax.swing.SwingUtilities.convertPoint(jsonlEditor().getComponent(), 0, 0, c).y);""")


def _island_bounds(path: str) -> tuple:
    """The island's outline in the painted image, in device pixels, cut just outside its antialiased edge.

    The docs-relevance skill's outline.py reads the background off the image's own edge and cuts on
    the first pixel that differs from it, so the whole rounded shape is in the crop.
    """
    return outline.outline_box(Image.open(path))


def _inside_corner(path: str, corner: tuple) -> int:
    """How far inside the island's top-left outline, at `corner` (left, top), a rectangle must start to hold no backdrop at all.

    The outline cut sits on the first pixel unlike the backdrop, so its outermost row and column are
    antialiased edge, and the rounded corner leaves a wedge of backdrop inside the box. For a circular
    corner the pixel of that wedge farthest from the arc's centre is the box's own corner, so the
    answer is the smallest square offset whose corner pixel, and the two beside it, are the island's.
    """
    im = Image.open(path).convert("RGB")
    px = im.load()
    left, top = corner
    bg = px[0, 0]
    island = lambda x, y: max(abs(px[left + x, top + y][i] - bg[i]) for i in range(3)) > 8
    for d in range(1, 2 * ISLAND_RADIUS):
        if island(d, d) and island(d + 1, d) and island(d, d + 1):
            return d
    raise RuntimeError(f"no offset within {2 * ISLAND_RADIUS} px clears the island's top-left corner")


# How far a cut through content may move inward to stop cutting a glyph in two, in device pixels.
REACH = 24


def _cleaned(im: Image.Image, box: tuple, clean: dict | None) -> tuple:
    """`box` with each side in `clean` moved inward, at most its reach, off any glyph it would cut in two.

    The image has to reach past every side cleaned, since a cut is judged by the line it drops.
    """
    edges = list(box)
    for side, reach in (clean or {}).items():
        edges[outline.SIDES.index(side)] = outline.clean_cut(im, tuple(edges), side, reach)
    return tuple(edges)


def _write_frame(im: Image.Image, box: tuple, dest: str, cut: tuple | None, clean: dict | None) -> None:
    """Crop `im` to `box` in device pixels, moving any `clean` side off the glyphs it would cut, save it
    at the published frames' density, and frame it."""
    box = _cleaned(im, box, clean)
    im.crop(box).save(dest, dpi=(DPI, DPI))
    print(f"wrote {os.path.relpath(dest, ROOT)} {box[2] - box[0]}x{box[3] - box[1]}")
    _finish(dest, cut)


def _crop_device(src: str, dest: str, box: tuple, cut: tuple | None = None, clean: dict | None = None) -> None:
    """Crop the painted `src` to `box` in device pixels and frame it."""
    _write_frame(Image.open(src), box, dest, cut, clean)


def _device(v: float) -> int:
    return round(v * SCALE)


# The editor island's corner radius in device pixels: the Islands theme's Island.arc is 20, an arc
# diameter in logical pixels, so the radius is 10 logical and 15 at the sandbox's 1.5 ratio.
ISLAND_RADIUS = 15


def _finish(dest: str, cut: tuple | None = None) -> None:
    """Commit the untouched frame as raw/<name>, then give it the hairline every published frame carries.

    A crop of the IDE's interior has no edge of its own, so a dark frame on a dark page (a README
    on github.com in dark mode) would have no visible boundary. `--require` refuses to write a
    frame whose four edges did not all end up carrying the colour. `cut` is given when the frame
    reaches the editor island's own edges: its corners there are the island's rounded ones, and
    the sides named in it are crop cuts whose corners stay square.
    """
    # The raw is committed beside the frame, so a later re-frame starts from it on either machine.
    raws = os.path.join(SHOTS_DIR, "raw")
    os.makedirs(raws, exist_ok=True)
    shutil.copyfile(dest, os.path.join(raws, os.path.basename(dest)))
    rounded = ["--radius", str(ISLAND_RADIUS)] + (["--cut", ",".join(cut)] if cut else []) if cut is not None else []
    subprocess.run([sys.executable, HAIRLINE, "--require", *rounded, dest], check=True)


def _crop(src: str, dest: str, box: tuple, clean: dict | None = None) -> None:
    """Crop the painted `src` to `box` given in logical pixels and frame it."""
    _crop_device(src, dest, tuple(_device(v) for v in box), clean=clean)


def _find(items: list, pred, what: str) -> dict:
    hits = [c for c in items if pred(c)]
    if not hits:
        raise RuntimeError(f"no component found for {what}")
    return hits[0]


def _toolbar_row(items: list) -> tuple[dict, list]:
    """The editor toolbar in a layout, and the components that sit wholly inside its bounds."""
    toolbar = _find(items, lambda c: c["cls"].endswith("ActionToolbarImpl"), "the editor toolbar")
    return toolbar, [c for c in items if c["y"] >= toolbar["y"] and c["y"] + c["h"] <= toolbar["y"] + toolbar["h"] and c is not toolbar]


def _scratch_path(name: str) -> str:
    """A file in the sandbox's work directory, for paints and captures that are not published."""
    os.makedirs(os.path.join(WORK, "scratch"), exist_ok=True)
    return os.path.join(WORK, "scratch", name)


def _shot_toolbar_panels() -> None:
    """The toolbar's pane pickers, Left = Formatted and Right = Inspect, cropped between the gear and Level."""
    _open_fixture(STARTUP_FIXTURE, _panes("FORMATTED", "INSPECT"), DEFAULT_CONFIG)
    _size_editor(1400, 600)
    full = _scratch_path("toolbar-panels-full.png")
    toolbar, in_bar = _toolbar_row(_paint_editor(full))
    left_label = _find(in_bar, lambda c: c["text"].startswith("Left panel"), "the Left panel label")
    level_label = _find(in_bar, lambda c: c["text"].startswith("Level"), "the Level label")
    buttons = [c for c in in_bar if c["cls"].endswith(".ActionButton")]
    before = [c for c in buttons if c["x"] + c["w"] <= left_label["x"]]
    after = [c for c in buttons if left_label["x"] < c["x"] and c["x"] + c["w"] <= level_label["x"]]
    gear_right = max(c["x"] + c["w"] for c in before)
    off_right = max(c["x"] + c["w"] for c in after)
    # The same margin on both sides: the one between the gear and the label, which is the tighter gap.
    x0 = (gear_right + left_label["x"]) / 2
    x1 = off_right + (left_label["x"] - x0)
    _crop(full, os.path.join(SHOTS_DIR, "toolbar-panels.png"), (x0, toolbar["y"], x1, toolbar["y"] + toolbar["h"]), clean={"left": REACH, "right": REACH})


def _line_top(line: int) -> float:
    """Top of logical line `line` (1-based) in the left pane, relative to the JSONL editor component."""
    return _js_json(f"""var ed = {_formatted_editor_expr()};
var p = javax.swing.SwingUtilities.convertPoint(ed.getContentComponent(), ed.logicalPositionToXY(new com.intellij.openapi.editor.LogicalPosition({line - 1}, 0)), jsonlEditor().getComponent());
writeJson(OUT, p.y);""")


def _visual_line_tops(count: int) -> list:
    """Tops of the first `count` visual (wrapped) lines of the left pane, relative to the JSONL editor."""
    return _js_json(f"""var ed = {_formatted_editor_expr()}, root = jsonlEditor().getComponent(), out = [];
for (var i = 0; i < {count}; i++) out.push(javax.swing.SwingUtilities.convertPoint(ed.getContentComponent(), 0, ed.visualLineToY(i), root).y);
writeJson(OUT, out);""")


def _park_caret() -> None:
    """Put the caret on the last line and scroll back to the top, so no caret-row highlight is in frame."""
    _js(f"""var ed = {_formatted_editor_expr()};
ed.getCaretModel().moveToOffset(ed.getDocument().getTextLength());
ed.getScrollingModel().scrollVertically(0);""")
    time.sleep(0.8)


def _shot_inspect_overlay() -> None:
    """The Inspect overlay over the Formatted pane: its corner toggle, close button, resize grip and divider."""
    _open_fixture(STARTUP_FIXTURE, _panes("FORMATTED", "INSPECT", overlay=(540, 300, "TOP")), DEFAULT_CONFIG)
    _size_editor(960, 520)
    _caret_to(7)  # the first "watching transcript" entry: nested fields and a path long enough to wrap
    full = _scratch_path("inspect-overlay-full.png")
    items = _paint_editor(full)
    # Found only to refuse a paint in which the overlay is not showing.
    _find(items, lambda c: c["cls"].endswith("$InspectOverlay"), "the Inspect overlay")
    divider = _find(items, lambda c: c["cls"].endswith("$OverlayDivider"), "the overlay divider")
    toolbar, _ = _toolbar_row(items)
    bottom = min(y for y in _visual_line_tops(40) if y >= divider["y"] + 30)
    _crop(full, os.path.join(SHOTS_DIR, "inspect-overlay.png"), (0, toolbar["y"] + toolbar["h"], 960, bottom), clean={"top": REACH, "bottom": REACH})


ALIGNMENTS = ["NONE", "TARGETS", "MESSAGES", "FIELDS"]


def _shot_alignment_levels() -> None:
    """The first eight entries under each Align level, the Formatted pane alone and soft-wrapped."""
    for level in ALIGNMENTS:
        config = {**DEFAULT_CONFIG, "alignment": f'E("Alignment", "{level}")'}
        _open_fixture(STARTUP_FIXTURE, _panes("FORMATTED", "NONE"), config)
        _size_editor(1100, 480)
        _park_caret()
        full = _scratch_path(f"align-{level.lower()}-full.png")
        items = _paint_editor(full)
        toolbar, _ = _toolbar_row(items)
        bar = [c for c in items if c["cls"].endswith("MyScrollBar") and c["h"] > 100]
        right = min(c["x"] for c in bar) if bar else 1100
        top = toolbar["y"] + toolbar["h"]
        _crop(full, os.path.join(SHOTS_DIR, f"align-{level.lower()}.png"), (0, top, right, _line_top(9)), clean={"top": REACH, "right": REACH, "bottom": REACH})


def _shot_raw_formatted() -> None:
    """Raw in the left pane beside Formatted in the right, the same entries line for line."""
    _open_fixture(STARTUP_FIXTURE, _panes("RAW", "FORMATTED", time_display="ABSOLUTE"), DEFAULT_CONFIG)
    _size_editor(1700, 480)
    _park_caret()
    full = _scratch_path("raw-formatted-full.png")
    editor_top = _paint_island(full)
    left, top, right, _ = _island_bounds(full)
    # Raw lines never wrap, so its line tops are boundaries in the Formatted pane beside it too. The
    # island's top corners are in frame and the bottom is a cut.
    _crop_device(full, os.path.join(SHOTS_DIR, "raw-formatted.png"), (left, top, right, _device(editor_top + _line_top(16))), cut=("bottom",), clean={"bottom": REACH})


def _raise_sandbox() -> None:
    """Bring the sandbox frame to the foreground with the shared Windows shutter, whose Screen method
    raises its target first. The picture it takes is thrown away; the raise is what is wanted."""
    title = _js_json("writeJson(OUT, String(frame().getTitle()));")
    _powershell(SHUTTER, "-TitleLike", title, "-Method", "Screen", "-First", "-Out", _scratch_path("raise.png"))
    time.sleep(0.8)


def _grab_screen(box: tuple, dest: str, clean: dict | None = None) -> None:
    """Read `box` (device screen pixels) off the desktop, so Windows' own popup border and shadow are in it.

    The `clean` sides are cuts through content, moved inward, at most their reach, off any glyph they
    would cut in two; the grab reaches one pixel past them, so a cut already clean can stay.
    """
    pad = {s: 1 if s in (clean or {}) else 0 for s in outline.SIDES}
    x0, y0, x1, y1 = box
    grabbed = ImageGrab.grab(bbox=(x0 - pad["left"], y0 - pad["top"], x1 + pad["right"], y1 + pad["bottom"]), all_screens=True)
    _write_frame(grabbed, (pad["left"], pad["top"], grabbed.width - pad["right"], grabbed.height - pad["bottom"]), dest, None, clean)


def _shot_gear_menu() -> None:
    """The gear popup open under its button, over the Formatted pane, with the Targets row highlighted.

    This one takes over the screen for a few seconds: it raises the sandbox and reads the desktop.
    """
    # Soft wrap off: under Align = Fields a wrapped line continues at the field column, which is
    # outside this crop, so wrapping would read as blank rows between the entries behind the popup.
    config = {**DEFAULT_CONFIG, "alignment": 'E("Alignment", "FIELDS")', "scrollToEndOnOpen": "true", "softWrapFormatted": "false"}
    _open_fixture(STARTUP_FIXTURE, _panes("FORMATTED", "INSPECT"), config)
    _size_editor(1400, 700)
    _park_caret()
    # Where the island's own outline sits, measured once off an in-process paint of it. The frame is a
    # plain rectangle cut out of the editor, not the island's shape, so it gets a square frame. It
    # starts just far enough inside the island's rounded top-left corner that no backdrop is in frame,
    # which keeps the gear icon and the whole of every line number in it.
    probe = _scratch_path("gear-menu-island.png")
    _paint_island(probe)
    ol, ot, _, _ = _island_bounds(probe)
    clear = _inside_corner(probe, (ol, ot))
    print(f"island corner cleared {clear} px inside its outline")
    _raise_sandbox()
    _js("""var gear = descendants(jsonlEditor().getComponent(), function (c) {
    return c instanceof com.intellij.openapi.actionSystem.impl.ActionButton && String(c.getAction().getTemplatePresentation().getText()) == "JSONL settings";
})[0];
gear.click();""")
    time.sleep(1.2)
    geo = _js_json("""var root = jsonlEditor().getComponent(), io = island().getLocationOnScreen();
var popup = null, wins = java.awt.Window.getWindows();
for (var i = 0; i < wins.length; i++) if (wins[i].isShowing() && wins[i] !== frame() && wins[i].getOwner() != null) popup = wins[i];
if (popup == null) throw "the gear popup did not open";
var list = descendants(popup, function (c) { return c instanceof javax.swing.JList; })[0], m = list.getModel();
for (var i = 0; i < m.getSize(); i++) if (String(m.getElementAt(i).getText()) == "Targets") list.setSelectedIndex(i);
writeJson(OUT, {x: root.getLocationOnScreen().x, ix: io.x, iy: io.y, popupBottom: popup.getLocationOnScreen().y + popup.getHeight(), items: layout(root)});""")
    time.sleep(0.6)
    _, in_bar = _toolbar_row(geo["items"])
    level_label = _find(in_bar, lambda c: c["text"].startswith("Level"), "the Level label")
    target_label = _find(in_bar, lambda c: c["text"].startswith("Target"), "the Target label")
    level_right = max(c["x"] + c["w"] for c in in_bar if level_label["x"] < c["x"] < target_label["x"])
    right = (level_right + target_label["x"]) / 2
    box = (_device(geo["ix"]) + ol + clear, _device(geo["iy"]) + ot + clear, _device(geo["x"] + right), _device(geo["popupBottom"] + 55))
    # The right cut may move off a glyph as far as it stays 4 px clear of the Level combo.
    reach_right = min(REACH, _device(geo["x"] + right) - _device(geo["x"] + level_right) - 4)
    _grab_screen(box, os.path.join(SHOTS_DIR, "gear-menu.png"), clean={"right": reach_right, "bottom": REACH})
    _js("""var ps = com.intellij.openapi.ui.popup.JBPopupFactory.getInstance().getChildPopups(frame());
for (var i = 0; i < ps.size(); i++) ps.get(i).cancel();""")


def _shot_main_hero() -> None:
    """The whole editor: Formatted beside the Inspect overlay, Level Debug, relative times in the toolbar.

    Subject: a five-hour dashboard session, fixtures/dashboard-session.jsonl, moved to end just
    before the capture; scrolled so the caret line, the intellij-jsonl-extension "watching
    transcript" entry, sits 24 lines below the top, as in the frame it replaced.
    """
    config = {**DEFAULT_CONFIG, "alignment": 'E("Alignment", "FIELDS")', "softWrapFormatted": "false"}
    _open_fixture(HERO_FIXTURE, _panes("FORMATTED", "INSPECT", overlay=(560, 260, "TOP"), time_display="RELATIVE"), config, to_now=True)
    # The level filter is per-file state with no stored default, so it is set on the open editor.
    _js("""var st = jsonlEditor().getState(com.intellij.openapi.fileEditor.FileEditorStateLevel.FULL);
st.setMinSeverity(E("Severity", "DEBUG"));
jsonlEditor().setState(st);""")
    time.sleep(1.0)
    _size_editor(1902, 952)
    line = _js_json(f"""var ed = {_formatted_editor_expr()}, doc = ed.getDocument(), hit = -1;
for (var i = 0; i < doc.getLineCount(); i++) {{
    var t = String(doc.getText(new com.intellij.openapi.util.TextRange(doc.getLineStartOffset(i), doc.getLineEndOffset(i))));
    if (t.indexOf("watching transcript") >= 0 && t.indexOf("chat_id=intellij-jsonl-extension") >= 0) hit = i;
}}
writeJson(OUT, hit + 1);""")
    if line < 25:
        raise RuntimeError(f"no intellij-jsonl-extension 'watching transcript' line far enough down (found {line})")
    _js(f"""var ed = {_formatted_editor_expr()};
ed.getCaretModel().moveToLogicalPosition(new com.intellij.openapi.editor.LogicalPosition({line - 1}, 0));
ed.getScrollingModel().scrollVertically(ed.logicalPositionToXY(new com.intellij.openapi.editor.LogicalPosition({line - 25}, 0)).y);""")
    time.sleep(1.2)
    full = _scratch_path("main-large-full.png")
    _paint_island(full)
    # The whole editor island is in frame, so all four corners are its own rounded ones: the crop
    # follows its outline, and the frame step clips the corners to that outline's radius.
    _crop_device(full, os.path.join(SHOTS_DIR, "main-large.png"), _island_bounds(full), cut=())


# The two Settings pages the docs show, by configurable id: both are named "JSONL Log Viewer", so a
# name would be ambiguous. The Color Scheme page's id is the colour options' own id followed by the
# ColorSettingsPage's class name.
TOOLS_PAGE = "com.olegs.jsonl.JsonlConfigurable"
COLOR_PAGE = "reference.settingsdialog.IDE.editor.colors.com.olegs.jsonl.JsonlColorSettingsPage"
# Both dialogs are one width, with one width of Settings tree, so the docs page shows their text at
# one size and their trees line up.
SETTINGS_WIDTH = 1050
SETTINGS_TREE_WIDTH = 300


def _open_settings(page_id: str, ready: str) -> None:
    """Open the Settings dialog at `page_id` and wait until it has settled.

    The dialog is modal, so it is scheduled rather than shown inside the robot's own EDT call, which
    would not return until the dialog closed. The remembered tree width and selected colour option
    are cleared first: the sandbox's IDE config outlives a launch, and either would change the shot.
    """
    _js("""if (settingsDialog() != null) throw "a Settings dialog is already open";
if (com.intellij.openapi.options.advanced.AdvancedSettings.getBoolean("ide.ui.non.modal.settings.window")) throw "Settings is set to open as an editor tab";
com.intellij.ide.util.PropertiesComponent.getInstance(project()).unsetValue("settings.editor.splitter.proportion");
com.intellij.ide.util.PropertiesComponent.getInstance().unsetValue("selected.color.option.type");
var U = com.intellij.ide.actions.ShowSettingsUtilImpl, p = project(), id = %s;
com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(new java.lang.Runnable({run: function () { U.showSettingsDialog(p, id, null); }}), com.intellij.openapi.application.ModalityState.nonModal());""" % json.dumps(page_id))
    _settle_settings(page_id, ready)


def _settle_settings(page_id: str, ready: str) -> None:
    """Wait until the dialog shows `page_id`, has finished loading, satisfies `ready` (a JS condition
    on `win`, the dialog's window) and lays out identically on two polls running; then paint it."""
    prev = [None]

    def ok() -> bool:
        try:
            s = _js_json(f"""var dw = settingsDialog();
if (dw == null) writeJson(OUT, {{open: false}}); else {{
    var win = dw.getWindow(), se = dw.getEditor(), ld = field(se, "loadingDecorator");
    var search = descendants(win, function (c) {{ return c instanceof com.intellij.ui.SearchTextField; }});
    writeJson(OUT, {{open: true, showing: win.isShowing(), page: String(se.getSelectedConfigurableId()), busy: ld.isLoading(),
        search: search.length ? String(search[0].getText()) : null, ready: !!({ready}), layout: JSON.stringify(layout(win))}});
}}""")
        except RobotError:
            return False
        good = s["open"] and s["showing"] and s["page"] == page_id and not s["busy"] and s["search"] == "" and s["ready"]
        same = good and s["layout"] == prev[0]
        prev[0] = s["layout"] if good else None
        return same

    _wait(f"the Settings dialog at {page_id}", ok, timeout=90)
    _js("var rp = settingsDialog().getWindow().getRootPane(); rp.paintImmediately(0, 0, rp.getWidth(), rp.getHeight());")
    time.sleep(0.3)


def _place_settings(width: int, height: int) -> None:
    """Size the dialog to `width` x `height` logical px on the work area, clear of the pointer, over the sandbox frame.

    A whole-window capture shows what lies behind the window in its rounded corners and through its
    translucent border, so the sandbox's own frame is put behind it rather than whatever the user has
    open. The dialog goes to the half of the work area the pointer is not in, since a window that opens
    under a resting pointer shows that control's hover state.
    """
    got = _js_json(f"""var win = settingsDialog().getWindow(), f = frame(), W = {width}, H = {height}, pad = 60;
var wa = com.intellij.ui.ScreenUtil.getScreenRectangle(f), m = java.awt.MouseInfo.getPointerInfo().getLocation();
if (W + 2 * pad > wa.width || H + 2 * pad > wa.height) throw "the work area " + wa.width + "x" + wa.height + " cannot hold a " + W + "x" + H + " dialog";
var x = m.x < wa.x + wa.width / 2 ? wa.x + wa.width - W - pad : wa.x + pad, y = wa.y + pad;
f.setExtendedState(java.awt.Frame.NORMAL); f.setBounds(x - pad, y - pad, W + 2 * pad, H + 2 * pad); f.validate();
win.setBounds(x, y, W, H); win.validate();
var sp = field(settingsDialog().getEditor(), "mySplitter"); sp.setProportion({SETTINGS_TREE_WIDTH} / (sp.getWidth() - sp.getDividerWidth())); win.validate();
writeJson(OUT, {{w: win.getWidth(), h: win.getHeight(), tree: sp.getFirstComponent().getWidth()}});""")
    if (got["w"], got["h"]) != (width, height):
        raise RuntimeError(f"the Settings dialog is {got['w']}x{got['h']}, wanted {width}x{height}")
    if abs(got["tree"] - SETTINGS_TREE_WIDTH) > 1:
        raise RuntimeError(f"the Settings tree is {got['tree']} px wide, wanted {SETTINGS_TREE_WIDTH}")


def _close_settings() -> None:
    _js("var dw = settingsDialog(); if (dw != null) dw.doCancelAction();")
    _wait("the Settings dialog to close", lambda: not _js_json("writeJson(OUT, settingsDialog() != null);"), timeout=15)


def _preview_settings(name: str) -> None:
    """Paint the staged dialog in-process to the work directory, to check it before any screen is read."""
    path = _scratch_path(f"{name}-preview.png")
    _js(f"paintPng(settingsDialog().getWindow().getRootPane(), {json.dumps(path)});")
    print(f"preview at {os.path.relpath(path, ROOT)}")


def _shoot_settings(name: str) -> None:
    """Capture the staged dialog as a whole window, frame it, then close it.

    This takes over the machine for a second or two: settings-dialog.ps1 runs the shared shutter's
    Screen method, which presses Alt, raises the dialog and reads the screen, with the pointer parked
    clear of the window meanwhile and put back after. The window's DPI goes into the PNG, and the frame
    step draws the border and corners from it, so a capture at any other DPI is refused.
    """
    try:
        geo = _js_json("""var win = settingsDialog().getWindow(), p = win.getLocationOnScreen();
writeJson(OUT, {hwnd: Number(com.sun.jna.Native.getWindowID(win)), x: p.x, y: p.y});""")
        park = (_device(geo["x"]) - 40, _device(geo["y"]) + 20)
        report = _powershell(os.path.join(SHOTS_DIR, "capture", "settings-dialog.ps1"), "-Hwnd", str(geo["hwnd"]), "-Out", os.path.join(SHOTS_DIR, name),
                            "-ParkX", str(park[0]), "-ParkY", str(park[1]))
        print(report.strip())
        if "method=Screen" not in report or f"dpi={DPI}" not in report:
            raise RuntimeError(f"the shutter did not report a Screen capture at {DPI} dpi, and the frame is drawn from that DPI")
    finally:
        _close_settings()


def _stage_settings_page() -> None:
    """Settings at Tools > JSONL Log Viewer, every option at its default, the whole page in view."""
    # Set before the dialog opens: the page binds to the settings when it is built, and a change after
    # that leaves Apply enabled. A fresh State is every default, the field paths included.
    _js("""settings().loadState(K("com.olegs.jsonl.JsonlSettings$State").newInstance());
settings().notifyChanged();""")
    ready = """descendants(win, function (c) { return c instanceof javax.swing.JCheckBox && textOf(c) == "Strip common target prefix" && c.isShowing(); }).length > 0"""
    _open_settings(TOOLS_PAGE, ready)
    # Tall enough that the page scrolls no more, with a little room under its last group.
    _place_settings(SETTINGS_WIDTH, 800)
    page = _settings_scroll("com.intellij.openapi.ui.DialogPanel")
    _place_settings(SETTINGS_WIDTH, 800 + page["need"] - page["have"] + 12)
    _settle_settings(TOOLS_PAGE, ready)
    for view in ("com.intellij.openapi.ui.DialogPanel", "com.intellij.openapi.options.newEditor.SettingsTreeView$MyTree"):
        if _settings_scroll(view)["bar"]:
            raise RuntimeError(f"the Settings dialog still scrolls {view}")
    state = _js_json("""var win = settingsDialog().getWindow();
writeJson(OUT, descendants(win, function (c) { return c.isShowing() && (c instanceof javax.swing.JCheckBox || c instanceof javax.swing.JTextField || c instanceof javax.swing.JComboBox || (c instanceof javax.swing.JButton && textOf(c) == "Apply")); }).map(function (c) {
    return c instanceof javax.swing.JCheckBox ? textOf(c) + "=" + c.isSelected() : c instanceof javax.swing.JComboBox ? "combo=" + String(c.getSelectedItem()) : c instanceof javax.swing.JButton ? "Apply=" + c.isEnabled() : "field=" + textOf(c);
}));""")
    want = {"combo=Targets", "field=0", "field=timestamp", "field=level", "field=target", "field=fields.message", "field=fields", "Apply=false", "Scroll to the latest entry when a .jsonl file is opened=false"}
    ticked = [s for s in state if s.endswith("=true") and not s.startswith("Apply")]
    if not want <= set(state) or len(ticked) != 7:
        raise RuntimeError(f"the Settings page is not at its defaults: {state}")


# The Color Scheme page's parts, found under the dialog's window `win`.
COLOR_PARTS = """var panel = descendants(win, function (c) { return c instanceof com.intellij.application.options.colors.NewColorAndFontPanel && c.isShowing(); })[0];
var tree = descendants(panel, function (c) { return c instanceof com.intellij.application.options.colors.ColorOptionsTree; })[0];
var split = descendants(panel, function (c) { return c instanceof com.intellij.ui.JBSplitter && c.getOrientation(); })[0];
var ed = descendants(panel, function (c) { return c instanceof com.intellij.openapi.editor.impl.EditorComponentImpl; })[0].getEditor();
"""
COLOR_DEMO_FIRST_LINE = "2026-04-22 14:31:32 INFO  http_server: listening addr=127.0.0.1:9077"


def _stage_settings_color_scheme() -> None:
    """Settings at Editor > Color Scheme > JSONL Log Viewer: scheme Dark, the ten keys with Level open, the demo whole."""
    ready = "descendants(win, function (c) { return c instanceof com.intellij.application.options.colors.ColorOptionsTree && c.isShowing(); }).length > 0 && " \
            "descendants(win, function (c) { return c instanceof com.intellij.application.options.colors.SchemesPanel && c.isShowing() && c.areSchemesLoaded(); }).length > 0"
    _open_settings(COLOR_PAGE, ready)
    _place_settings(SETTINGS_WIDTH, 900)
    # Level open and nothing selected: a selected key blinks its ranges in the demo, so a screen read
    # could catch either phase, and it would fill the attribute editor beside the tree.
    _js(f"""var win = settingsDialog().getWindow();
{COLOR_PARTS}
for (var i = 0; i < tree.getRowCount(); i++) if (String(tree.getPathForRow(i).getLastPathComponent()) == "Level") {{ tree.expandRow(i); break; }}
com.intellij.ide.util.PropertiesComponent.getInstance().unsetValue("selected.color.option.type");
tree.clearSelection();
ed.getCaretModel().moveToOffset(0); ed.getScrollingModel().scrollVertically(0); ed.getScrollingModel().scrollHorizontally(0);""")
    _settle_settings(COLOR_PAGE, ready)
    # Split the page so the key tree holds its rows with one row to spare and the demo its five lines
    # with one to spare, then grow or shrink the dialog by what that split needs.
    m = _js_json(f"""var win = settingsDialog().getWindow();
{COLOR_PARTS}
var tp = javax.swing.SwingUtilities.convertPoint(tree.getParent(), tree.getX(), tree.getY(), split);
writeJson(OUT, {{h: win.getHeight(), minH: win.getMinimumSize().height, split: split.getHeight(), divider: split.getDividerWidth(), treeTop: tp.y, treeNeed: tree.getPreferredSize().height, rowH: tree.getRowHeight(),
    edChrome: split.getSecondComponent().getHeight() - ed.getScrollingModel().getVisibleArea().height, lineH: ed.getLineHeight(), lines: ed.getDocument().getLineCount()}});""")
    top = m["treeTop"] + m["treeNeed"] + m["rowH"]
    bottom = m["edChrome"] + (m["lines"] + 1) * m["lineH"]
    split = top + m["divider"] + bottom
    # The dialog has a minimum height; below it, the demo takes the rows the dialog will not give up.
    _place_settings(SETTINGS_WIDTH, max(m["minH"], m["h"] + split - m["split"]))
    _js(f"""var win = settingsDialog().getWindow();
{COLOR_PARTS}
split.setProportion({top} / (split.getHeight() - split.getDividerWidth())); win.validate();
// The Settings tree keeps its selected row in view; centre it, so the rows around it show too.
var st = settingsDialog().getEditor().getTreeView().getTree(), r = st.getRowBounds(st.getLeadSelectionRow()), vp = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport, st);
vp.setViewPosition(new java.awt.Point(0, Math.max(0, Math.min(st.getHeight() - vp.getHeight(), r.y + r.height / 2 - vp.getHeight() / 2))));
ed.getCaretModel().moveToOffset(0); ed.getScrollingModel().scrollVertically(0); ed.getScrollingModel().scrollHorizontally(0);""")
    _settle_settings(COLOR_PAGE, ready)
    state = _js_json(f"""var win = settingsDialog().getWindow();
{COLOR_PARTS}
var rows = []; for (var i = 0; i < tree.getRowCount(); i++) rows.push(String(tree.getPathForRow(i).getLastPathComponent()));
var treeSp = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JScrollPane, tree), vis = ed.getScrollingModel().getVisibleArea();
var combo = descendants(panel, function (c) {{ return c instanceof javax.swing.JComboBox && c.isShowing(); }})[0];
var apply = descendants(win, function (c) {{ return c instanceof javax.swing.JButton && textOf(c) == "Apply"; }})[0];
var st = settingsDialog().getEditor().getTreeView().getTree(), sr = st.getRowBounds(st.getLeadSelectionRow()), svp = javax.swing.SwingUtilities.getAncestorOfClass(javax.swing.JViewport, st).getViewRect();
writeJson(OUT, {{rows: rows, selected: tree.getSelectionCount(), treeBar: treeSp != null && treeSp.getVerticalScrollBar().isVisible(), shownLines: Math.floor(vis.height / ed.getLineHeight()),
    fits: vis.width >= ed.getContentComponent().getPreferredSize().width, first: String(ed.getDocument().getText(new com.intellij.openapi.util.TextRange(0, ed.getDocument().getLineEndOffset(0)))),
    scheme: combo ? String(combo.getSelectedItem().getPresentableText()) : null, apply: apply.isEnabled(), selectedInView: svp.contains(sr)}});""")
    want_rows = ["Equals sign", "Field name", "Level", "Debug", "Error", "Info", "Trace", "Warn", "Message", "Target", "Timestamp"]
    problems = [p for p, bad in [
        (f"key rows {state['rows']}", state["rows"] != want_rows), (f"{state['selected']} keys selected", state["selected"] != 0),
        ("the key tree scrolls", state["treeBar"]), (f"the demo shows {state['shownLines']} lines", state["shownLines"] < 5),
        ("the demo scrolls sideways", not state["fits"]), (f"the demo starts {state['first']!r}", state["first"] != COLOR_DEMO_FIRST_LINE),
        (f"the scheme reads {state['scheme']!r}", state["scheme"] != "Dark"), ("Apply is enabled", state["apply"]),
        ("JSONL Log Viewer is out of view in the Settings tree", not state["selectedInView"])] if bad]
    if problems:
        raise RuntimeError("the Color Scheme page is not staged: " + "; ".join(problems))


def _settings_scroll(view: str) -> dict:
    """How tall the scroll pane holding `view` (a class name) needs to be, and is, in the open dialog."""
    return _js_json(f"""var sp = descendants(settingsDialog().getWindow(), function (c) {{
    return c instanceof javax.swing.JScrollPane && c.isShowing() && String(c.getViewport().getView().getClass().getName()) == {json.dumps(view)};
}})[0];
if (sp == null) throw "no scroll pane holds a " + {json.dumps(view)};
var v = sp.getViewport();
writeJson(OUT, {{need: v.getView().getPreferredSize().height, have: v.getExtentSize().height, bar: sp.getVerticalScrollBar().isVisible()}});""")


# The shots that capture a Settings dialog off the screen: how each is staged, and the name its frame
# is published under. `preview` stages one without the capture, so the staging can be checked first:
# the dialog is painted in-process to the work directory and left open.
DIALOG_SHOTS = {
    "settings-page": (_stage_settings_page, "settings"),
    "settings-color-scheme": (_stage_settings_color_scheme, "settings-color-scheme"),
}


def _shoot_dialog(key: str) -> None:
    """Stage the Settings dialog shot `key` and capture it as a whole window. Takes over the screen for a moment."""
    stage, name = DIALOG_SHOTS[key]
    stage()
    _shoot_settings(f"{name}.png")


SHOTS = {
    "main-hero": _shot_main_hero,
    "toolbar-panels": _shot_toolbar_panels,
    "gear-menu": _shot_gear_menu,
    "inspect-overlay": _shot_inspect_overlay,
    "alignment-levels": _shot_alignment_levels,
    "raw-formatted": _shot_raw_formatted,
    **{key: functools.partial(_shoot_dialog, key) for key in DIALOG_SHOTS},
}


def main() -> None:
    args = sys.argv[1:]
    if not args:
        sys.exit(__doc__)
    if args[0] == "preview":
        for a in args[1:]:
            if a not in DIALOG_SHOTS:
                sys.exit(f"no preview for {a!r}; known: {', '.join(DIALOG_SHOTS)}")
            _close_settings()
            stage, name = DIALOG_SHOTS[a]
            stage()
            _preview_settings(name)
        return
    for a in args:
        if a == "launch":
            _launch()
        elif a == "stop":
            _stop()
        elif a in SHOTS:
            SHOTS[a]()
        else:
            sys.exit(f"unknown shot {a!r}; known: launch, stop, preview, {', '.join(SHOTS)}")


if __name__ == "__main__":
    main()
