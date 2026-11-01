import asyncio, json, socket, threading, tkinter as tk
from tkinter import ttk, messagebox
import websockets

PORT = 8765
clients = set()
loop = None

async def handler(ws):
    clients.add(ws)
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
                if msg.get("type") == "hello":
                    print("TV connected:", msg)
            except Exception:
                pass
    finally:
        clients.discard(ws)

async def server():
    async with websockets.serve(handler, "0.0.0.0", PORT):
        await asyncio.Future()

def start_server():
    global loop
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(server())

def pc_ip():
    s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        s.connect(("192.0.2.1", 80))
        return s.getsockname()[0]
    except Exception:
        return "Run ipconfig to find your IPv4 address"
    finally:
        s.close()

def send(command):
    if not loop or not clients:
        messagebox.showwarning("TV not connected", "Open TV Control Centre on the TV and check both devices are on the same network.")
        return
    async def broadcast():
        await asyncio.gather(*(c.send(json.dumps({"command": command})) for c in list(clients)), return_exceptions=True)
    asyncio.run_coroutine_threadsafe(broadcast(), loop)

def main():
    threading.Thread(target=start_server, daemon=True).start()
    app = tk.Tk()
    app.title("TV Control Centre — Windows Companion")
    app.geometry("640x510")
    app.configure(bg="#071b13")
    style = ttk.Style(); style.theme_use("clam")
    style.configure("TFrame", background="#071b13")
    style.configure("TLabel", background="#071b13", foreground="#e8fff1", font=("Segoe UI", 11))
    style.configure("Title.TLabel", font=("Segoe UI", 22, "bold"))
    style.configure("TButton", font=("Segoe UI", 12), padding=12)
    box = ttk.Frame(app, padding=24); box.pack(fill="both", expand=True)
    ttk.Label(box, text="TV CONTROL CENTRE", style="Title.TLabel").pack(anchor="w")
    ttk.Label(box, text="Windows companion · Local network control").pack(anchor="w", pady=(4, 14))
    ttk.Label(box, text=f"PC IPv4: {pc_ip()}    WebSocket port: {PORT}").pack(anchor="w")
    ttk.Label(box, text="Set this IPv4 address as the companion host in MainActivity.kt, then rebuild the TV app.").pack(anchor="w", pady=(6, 18))
    grid = ttk.Frame(box); grid.pack(fill="x")
    items = [("Ambient Mode", "ambient"), ("Dashboard", "dashboard"),
             ("Wireless Screen Share", "screen_share"), ("HDMI 3 Backup", "hdmi3"),
             ("Next Wallpaper", "next_wallpaper")]
    for n, (label, cmd) in enumerate(items):
        ttk.Button(grid, text=label, command=lambda c=cmd: send(c)).grid(row=n//2, column=n%2, sticky="ew", padx=5, pady=5)
    grid.columnconfigure(0, weight=1); grid.columnconfigure(1, weight=1)
    ttk.Separator(box).pack(fill="x", pady=18)
    ttk.Label(box, text="Live TV mirror — planned experimental module", font=("Segoe UI", 12, "bold")).pack(anchor="w")
    ttk.Label(box, text="Requires TV-side MediaProjection permission and a video transport. Protected apps may block capture. This starter does not yet stream video.").pack(anchor="w", pady=8)
    ttk.Label(box, text="Background images can currently be selected on the TV. PC-to-TV image transfer is a subsequent milestone.").pack(anchor="w")
    app.mainloop()

if __name__ == "__main__":
    main()
