import subprocess, re, time, os, xml.etree.ElementTree as ET
ADB = os.environ.get("ADB") or os.path.expanduser("~/.local/share/axis-toolchain/android-sdk/platform-tools/adb")

def adb(*a, timeout=60):
    return subprocess.run([ADB, *a], capture_output=True, text=True, timeout=timeout).stdout

def dump():
    adb("shell", "uiautomator", "dump", "/sdcard/ui.xml")
    x = adb("shell", "cat", "/sdcard/ui.xml")
    return ET.fromstring(x[x.index("<?xml"):] if "<?xml" in x else x)

def nodes():
    out = []
    for n in dump().iter("node"):
        m = re.match(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]", n.get("bounds", ""))
        if m:
            x1, y1, x2, y2 = map(int, m.groups())
            out.append(dict(text=n.get("text", ""), desc=n.get("content-desc", ""), cls=n.get("class", ""),
                            clickable=n.get("clickable") == "true", enabled=n.get("enabled") == "true",
                            cx=(x1 + x2) // 2, cy=(y1 + y2) // 2, y=y1))
    return out

def texts():
    return [n["text"] for n in nodes() if n["text"]]

def tap(n): adb("shell", "input", "tap", str(n["cx"]), str(n["cy"]))

def find(sub, ns=None):
    for n in (ns or nodes()):
        if sub.lower() in n["text"].lower() or sub.lower() in n["desc"].lower():
            return n

def tap_text(sub, wait=1.0):
    n = find(sub)
    if n: tap(n); time.sleep(wait)
    return bool(n)

def tap_edit(index=0):
    eds = [n for n in nodes() if n["cls"].endswith("EditText")]
    if len(eds) > index: tap(eds[index]); time.sleep(0.5); return True
    return False

def type_text(t):
    esc = re.sub(r"([\\&()<>|;*~'\"` $?#!{}\[\]])", r"\\\1", t.replace(" ", "%s"))
    adb("shell", "input", "text", esc)

def env_key(name):
    for line in open(os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", ".env")):
        if line.startswith(name + "="):
            return line.split("=", 1)[1].strip().strip('"\'')

def wait_for(sub, secs=60):
    end = time.time() + secs
    while time.time() < end:
        if find(sub): return True
        time.sleep(2)
    return False
