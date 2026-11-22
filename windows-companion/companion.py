"""TV Control Centre Windows companion — local-network remote panel."""
import asyncio
import json
import socket
import threading
import time
import tkinter as tk
from tkinter import ttk, messagebox
import websockets

PORT = 8765
clients = set()
last_heartbeat = {}
loop = None

async def handler(ws):
    clients.add(ws)
    last_heartbeat[ws] = time.monotonic()
    try:
        async for raw in ws:
            try:
                msg = json.loads(raw)
                if msg.get("type") in ("hello", "heartbeat"):
                    last_heartbeat[ws] = time.monotonic()
                    if msg.get("type") == "hello":
                        print("TV connected:", msg)
            except (ValueError, TypeError):
                pass
    finally:
        clients.discard(ws)
        last_heartbeat.pop(ws, None)

async def server():
    async with websockets.serve(handler, "0.0.0.0", PORT, ping_interval=20, ping_timeout=20):
        await asyncio.Future()

def start_server():
    global loop
    loop = asyncio.new_event_loop()
    asyncio.set_event_loop(loop)
    loop.run_until_complete(server())

def pc_ip():
    sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
    try:
        sock.connect(("192.0.2.1", 80))
        return sock.getsockname()[0]
    except OSError:
        return "Unavailable — check network"
    finally:
        sock.close()

def send(command):
    if not loop or not clients:
        messagebox.showwarning("TV not connected", "Open TV Control Centre on the TV and check that both devices are on the same network.")
        return
    async def broadcast():
        await asyncio.gather(*(client.send(json.dumps({"command": command})) for client in list(clients)), return_exceptions=True)
    asyncio.run_coroutine_threadsafe(broadcast(), loop)

def main():
    threading.Thread(target=start_server, daemon=True).start()
    app = tk.Tk()
    app.title("TV Control Centre | Windows Companion")
    app.geometry("760x650")
    app.minsize(650, 560)
    app.configure(bg="#081710")

    style = ttk.Style()
    style.theme_use("clam")
    style.configure("TFrame", background="#081710")
    style.configure("Panel.TFrame", background="#10251b")
    style.configure("TLabel", background="#081710", foreground="#e8f5ec", font=("Segoe UI", 10))
    style.configure("Title.TLabel", font=("Segoe UI", 23, "bold"), foreground="#f0fff4")
    style.configure("Sub.TLabel", foreground="#a9c8b4")
    style.configure("Section.TLabel", background="#10251b", foreground="#8fe0aa", font=("Segoe UI", 11, "bold"))
    style.configure("TButton", font=("Segoe UI", 10, "bold"), padding=(10, 12), background="#1e6844", foreground="#ffffff")
    style.map("TButton", background=[("active", "#2b875a"), ("disabled", "#35483d")])

    outer = ttk.Frame(app, padding=24)
    outer.pack(fill="both", expand=True)
    ttk.Label(outer, text="TV CONTROL CENTRE", style="Title.TLabel").pack(anchor="w")
    ttk.Label(outer, text="WINDOWS COMPANION  ·  LOCAL NETWORK CONTROL", style="Sub.TLabel").pack(anchor="w", pady=(4, 18))

    network = ttk.Frame(outer, style="Panel.TFrame", padding=16)
    network.pack(fill="x", pady=(0, 18))
    ttk.Label(network, text="CONNECTION", style="Section.TLabel").pack(anchor="w")
    ttk.Label(network, text=f"PC IPv4 address: {pc_ip()}     WebSocket port: {PORT}", style="TLabel").pack(anchor="w", pady=(8, 3))
    ttk.Label(network, text="Enter this IPv4 address in the TV app: Dashboard → Windows Companion Settings.", style="Sub.TLabel").pack(anchor="w")
    health_status = ttk.Label(network, text="TV health: waiting for connection…", style="Section.TLabel")
    health_status.pack(anchor="w", pady=(10, 0))

    def refresh_health_status():
        now = time.monotonic()
        recent = [stamp for stamp in last_heartbeat.values() if now - stamp <= 15]
        if recent:
            health_status.configure(text="TV health: app responsive · heartbeat received")
        elif clients:
            health_status.configure(text="TV health: no recent app heartbeat · possible app/UI stall")
        else:
            health_status.configure(text="TV health: TV app disconnected or network unavailable")
        app.after(3000, refresh_health_status)

    panel = ttk.Frame(outer, style="Panel.TFrame", padding=16)
    panel.pack(fill="both", expand=True)
    ttk.Label(panel, text="TV CONTROLS", style="Section.TLabel").pack(anchor="w", pady=(0, 12))
    controls = ttk.Frame(panel, style="Panel.TFrame")
    controls.pack(fill="both", expand=True)
    items = [
        ("Dashboard", "dashboard"), ("Ambient Mode", "ambient"),
        ("Screen Share", "screen_share"), ("Wi-Fi Recovery", "wifi_recovery"),
        ("HDMI 1", "hdmi1"), ("HDMI 2", "hdmi2"),
        ("HDMI 3 · Backup", "hdmi3"), ("HDMI 4", "hdmi4"),
        ("Next Background", "next_wallpaper"),
    ]
    for index, (label, command) in enumerate(items):
        ttk.Button(controls, text=label, command=lambda cmd=command: send(cmd)).grid(
            row=index // 3, column=index % 3, sticky="nsew", padx=5, pady=5
        )
    for column in range(3):
        controls.columnconfigure(column, weight=1)
    for row in range(3):
        controls.rowconfigure(row, weight=1)

    ttk.Label(outer, text="LIVE TV MIRROR  ·  NOT IMPLEMENTED", font=("Segoe UI", 10, "bold"), foreground="#f0c778").pack(anchor="w", pady=(16, 4))
    ttk.Label(outer, text="Video mirroring needs a TV-side capture permission flow and streaming pipeline. Protected apps may prevent capture.", style="Sub.TLabel", wraplength=700).pack(anchor="w")
    ttk.Label(outer, text="Keep the TV and PC on the same Wi-Fi/LAN. Allow Python through Windows Firewall on Private networks.", style="Sub.TLabel", wraplength=700).pack(anchor="w", pady=(10, 0))
    app.after(1000, refresh_health_status)
    app.mainloop()

if __name__ == "__main__":
    main()
