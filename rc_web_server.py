import socket
import threading
import time
import json
import sys
from datetime import datetime
from http.server import HTTPServer, BaseHTTPRequestHandler
import urllib.parse

import os

# Get port from environment (Render) or use default
LISTEN_PORT = int(os.environ.get('PORT', 8080))

phone_addr = None
phone_last_seen = 0
is_connected = False

# RC Control structure
class RCControls:
    def __init__(self):
        self.throttle = 0.0      # 0.0 to 1.0
        self.aileron = 0.0       # -1.0 to 1.0 (left/right)
        self.elevator = 0.0      # -1.0 to 1.0 (up/down)
        self.rudder = 0.0        # -1.0 to 1.0 (left/right)
        self.armed = False       # Safety switch
        self.flight_mode = 0     # 0=manual, 1=stabilized, 2=auto
        
    def to_json(self):
        return json.dumps({
            'throttle': self.throttle,
            'aileron': self.aileron,
            'elevator': self.elevator,
            'rudder': self.rudder,
            'armed': self.armed,
            'flight_mode': self.flight_mode,
            'timestamp': datetime.now().isoformat()
        })

# Global RC controls
rc_controls = RCControls()

class RCHTTPHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        global phone_addr, phone_last_seen, is_connected, rc_controls
        
        parsed_path = urllib.parse.urlparse(self.path)
        path = parsed_path.path
        
        if path == '/':
            # Main control page
            self.send_response(200)
            self.send_header('Content-type', 'text/html')
            self.end_headers()
            
            html = f"""
<!DOCTYPE html>
<html>
<head>
    <title>RC Plane Control</title>
    <meta charset="utf-8">
    <style>
        body {{ font-family: Arial, sans-serif; margin: 20px; background: #f0f0f0; }}
        .container {{ max-width: 800px; margin: 0 auto; background: white; padding: 20px; border-radius: 10px; box-shadow: 0 2px 10px rgba(0,0,0,0.1); }}
        .status {{ padding: 10px; margin: 10px 0; border-radius: 5px; }}
        .connected {{ background: #d4edda; color: #155724; border: 1px solid #c3e6cb; }}
        .disconnected {{ background: #f8d7da; color: #721c24; border: 1px solid #f5c6cb; }}
        .controls {{ display: grid; grid-template-columns: 1fr 1fr; gap: 20px; margin: 20px 0; }}
        .control-group {{ background: #f8f9fa; padding: 15px; border-radius: 5px; }}
        .control-group h3 {{ margin-top: 0; color: #495057; }}
        .slider {{ width: 100%; margin: 10px 0; }}
        .button {{ background: #007bff; color: white; border: none; padding: 10px 20px; border-radius: 5px; cursor: pointer; margin: 5px; }}
        .button:hover {{ background: #0056b3; }}
        .button.danger {{ background: #dc3545; }}
        .button.danger:hover {{ background: #c82333; }}
        .button.success {{ background: #28a745; }}
        .button.success:hover {{ background: #218838; }}
        .value-display {{ font-weight: bold; color: #007bff; }}
        .telemetry {{ background: #e9ecef; padding: 10px; border-radius: 5px; margin: 10px 0; }}
    </style>
</head>
<body>
    <div class="container">
        <h1>🎮 RC Plane Control</h1>
        
        <div class="status {'connected' if is_connected else 'disconnected'}">
            <strong>Status:</strong> {'✓ Phone Connected' if is_connected else '❌ No Phone Connected'}
            <br><small>Phone IP: {phone_addr[0] if phone_addr else 'None'}</small>
        </div>
        
        <div class="controls">
            <div class="control-group">
                <h3>🎯 Flight Controls</h3>
                <label>Throttle: <span class="value-display" id="throttle-val">0.00</span></label>
                <input type="range" class="slider" id="throttle" min="0" max="100" value="0" oninput="updateControl('throttle', this.value/100)">
                
                <label>Rudder: <span class="value-display" id="rudder-val">0.00</span></label>
                <input type="range" class="slider" id="rudder" min="-100" max="100" value="0" oninput="updateControl('rudder', this.value/100)">
                
                <label>Elevator: <span class="value-display" id="elevator-val">0.00</span></label>
                <input type="range" class="slider" id="elevator" min="-100" max="100" value="0" oninput="updateControl('elevator', this.value/100)">
                
                <label>Aileron: <span class="value-display" id="aileron-val">0.00</span></label>
                <input type="range" class="slider" id="aileron" min="-100" max="100" value="0" oninput="updateControl('aileron', this.value/100)">
            </div>
            
            <div class="control-group">
                <h3>⚙️ System Controls</h3>
                <button class="button {'success' if rc_controls.armed else 'danger'}" onclick="toggleArm()">
                    {'🛑 DISARM' if rc_controls.armed else '🚀 ARM'}
                </button>
                <br>
                <button class="button" onclick="cycleFlightMode()">
                    Flight Mode: {['MANUAL', 'STABILIZED', 'AUTO'][rc_controls.flight_mode]}
                </button>
                <br>
                <button class="button" onclick="sendCommand('test')">Test Command</button>
                <button class="button" onclick="sendCommand('status')">Get Status</button>
            </div>
        </div>
        
        <div class="telemetry">
            <h3>📊 Current RC Values</h3>
            <pre id="rc-values">{rc_controls.to_json()}</pre>
        </div>
        
        <div class="telemetry">
            <h3>📡 Last Telemetry</h3>
            <pre id="telemetry">No telemetry received yet...</pre>
        </div>
    </div>

    <script>
        function updateControl(control, value) {{
            document.getElementById(control + '-val').textContent = value.toFixed(2);
            fetch('/api/control', {{
                method: 'POST',
                headers: {{'Content-Type': 'application/json'}},
                body: JSON.stringify({{control: control, value: value}})
            }});
        }}
        
        function toggleArm() {{
            fetch('/api/arm', {{method: 'POST'}});
        }}
        
        function cycleFlightMode() {{
            fetch('/api/flightmode', {{method: 'POST'}});
        }}
        
        function sendCommand(cmd) {{
            fetch('/api/command', {{
                method: 'POST',
                headers: {{'Content-Type': 'application/json'}},
                body: JSON.stringify({{command: cmd}})
            }});
        }}
        
        // Auto-refresh status every 2 seconds
        setInterval(() => {{
            fetch('/api/status')
                .then(response => response.json())
                .then(data => {{
                    document.getElementById('rc-values').textContent = JSON.stringify(data.rc_controls, null, 2);
                    if (data.telemetry) {{
                        document.getElementById('telemetry').textContent = JSON.stringify(data.telemetry, null, 2);
                    }}
                }});
        }}, 2000);
        // ===== Gamepad support (Xbox controller via browser) =====
        let prevButtons = {};
        let gamepadConnected = false;
        let lastSent = 0;
        const sendIntervalMs = 33; // ~30Hz

        window.addEventListener('gamepadconnected', (e) => {
            gamepadConnected = true;
            console.log('Gamepad connected:', e.gamepad.id);
        });
        window.addEventListener('gamepaddisconnected', () => {
            gamepadConnected = false;
            console.log('Gamepad disconnected');
        });

        function applyDeadzone(value, dz = 0.06) {
            if (Math.abs(value) < dz) return 0;
            return value;
        }

        function pollGamepadAndSend() {
            const now = performance.now();
            if (!gamepadConnected || (now - lastSent) < sendIntervalMs) {
                requestAnimationFrame(pollGamepadAndSend);
                return;
            }

            const pads = navigator.getGamepads ? navigator.getGamepads() : [];
            const gp = pads && pads[0];
            if (!gp) {
                requestAnimationFrame(pollGamepadAndSend);
                return;
            }

            // Xbox standard mapping
            const lsx = applyDeadzone(gp.axes[0] || 0); // rudder
            const lsy = applyDeadzone(gp.axes[1] || 0); // throttle (invert)
            const rsx = applyDeadzone(gp.axes[2] || 0); // aileron
            const rsy = applyDeadzone(gp.axes[3] || 0); // elevator

            const throttle = (1 - lsy) / 2; // map -1..1 to 1..0 then 0..1
            const rudder = lsx;
            const aileron = rsx;
            const elevator = -rsy; // up is negative on most pads

            // Buttons: A (0) toggles armed, B (1) cycles flight mode
            const btnA = gp.buttons[0]?.pressed;
            const btnB = gp.buttons[1]?.pressed;
            let toggleArm = false;
            let cycleMode = false;
            if (btnA && !prevButtons[0]) toggleArm = true;
            if (btnB && !prevButtons[1]) cycleMode = true;
            prevButtons[0] = !!btnA;
            prevButtons[1] = !!btnB;

            const payload = { throttle, rudder, elevator, aileron, toggleArm, cycleMode };

            fetch('/api/controls', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(payload)
            }).catch(() => {});

            // Update UI values
            const setVal = (id, v) => {
                const el = document.getElementById(id);
                if (el) el.textContent = v.toFixed(2);
            };
            setVal('throttle-val', throttle);
            setVal('rudder-val', rudder);
            setVal('elevator-val', elevator);
            setVal('aileron-val', aileron);

            lastSent = now;
            requestAnimationFrame(pollGamepadAndSend);
        }

        requestAnimationFrame(pollGamepadAndSend);
    </script>
</body>
</html>
            """
            self.wfile.write(html.encode())
            
        elif path == '/api/status':
            # API endpoint for status
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            
            status_data = {
                'connected': is_connected,
                'phone_ip': phone_addr[0] if phone_addr else None,
                'rc_controls': json.loads(rc_controls.to_json()),
                'telemetry': None  # TODO: Add telemetry
            }
            self.wfile.write(json.dumps(status_data).encode())
            
        elif path == '/api/control':
            # API endpoint for control updates
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            data = json.loads(post_data.decode())
            
            if data['control'] == 'throttle':
                rc_controls.throttle = data['value']
            elif data['control'] == 'rudder':
                rc_controls.rudder = data['value']
            elif data['control'] == 'elevator':
                rc_controls.elevator = data['value']
            elif data['control'] == 'aileron':
                rc_controls.aileron = data['value']
                
            # Send to phone
            if phone_addr:
                send_rc_controls_to_phone()
                
            self.wfile.write(json.dumps({'status': 'ok'}).encode())

        elif path == '/api/controls':
            # Bulk controls endpoint for browser Gamepad
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()

            try:
                content_length = int(self.headers.get('Content-Length', 0))
                post_data = self.rfile.read(content_length) if content_length > 0 else b'{}'
                data = json.loads(post_data.decode())

                rc_controls.throttle = float(data.get('throttle', rc_controls.throttle))
                rc_controls.rudder = float(data.get('rudder', rc_controls.rudder))
                rc_controls.elevator = float(data.get('elevator', rc_controls.elevator))
                rc_controls.aileron = float(data.get('aileron', rc_controls.aileron))

                if data.get('toggleArm'):
                    rc_controls.armed = not rc_controls.armed
                if data.get('cycleMode'):
                    rc_controls.flight_mode = (rc_controls.flight_mode + 1) % 3

                # Optional push to phone if reachable
                if phone_addr:
                    send_rc_controls_to_phone()

                self.wfile.write(json.dumps({'status': 'ok'}).encode())
            except Exception as e:
                self.wfile.write(json.dumps({'status': 'error', 'message': str(e)}).encode())
            
        elif path == '/api/arm':
            # API endpoint for arm/disarm
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            
            rc_controls.armed = not rc_controls.armed
            if phone_addr:
                send_rc_controls_to_phone()
                
            self.wfile.write(json.dumps({'status': 'ok', 'armed': rc_controls.armed}).encode())
            
        elif path == '/api/flightmode':
            # API endpoint for flight mode
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            
            rc_controls.flight_mode = (rc_controls.flight_mode + 1) % 3
            if phone_addr:
                send_rc_controls_to_phone()
                
            self.wfile.write(json.dumps({'status': 'ok', 'flight_mode': rc_controls.flight_mode}).encode())
            
        elif path == '/api/command':
            # API endpoint for custom commands
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            
            content_length = int(self.headers['Content-Length'])
            post_data = self.rfile.read(content_length)
            data = json.loads(post_data.decode())
            
            if phone_addr:
                send_command_to_phone(data['command'])
                
            self.wfile.write(json.dumps({'status': 'ok'}).encode())
            
        elif path == '/phone/keepalive':
            # Phone keep-alive endpoint
            global phone_addr, phone_last_seen, is_connected
            phone_addr = (self.client_address[0], self.client_address[1])
            phone_last_seen = time.time()
            
            if not is_connected:
                print(f"✓ Phone connected from {phone_addr[0]}:{phone_addr[1]}")
                is_connected = True
            else:
                print(f"✓ Phone keep-alive from {phone_addr[0]}:{phone_addr[1]}")
            
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'status': 'ok'}).encode())
            
        elif path == '/api/get_controls':
            # ESP32/phone pull endpoint
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()

            self.wfile.write(rc_controls.to_json().encode())

        elif path == '/phone/telemetry':
            # Phone telemetry endpoint
            content_length = int(self.headers.get('Content-Length', 0))
            if content_length > 0:
                post_data = self.rfile.read(content_length)
                try:
                    telemetry = json.loads(post_data.decode())
                    print(f"📊 Telemetry: {telemetry}")
                except:
                    print(f"📨 Raw telemetry: {post_data.decode()}")
            
            self.send_response(200)
            self.send_header('Content-type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({'status': 'ok'}).encode())
            
        else:
            self.send_response(404)
            self.end_headers()
            self.wfile.write(b'Not found')

def send_rc_controls_to_phone():
    """Send RC controls to phone via HTTP"""
    global phone_addr, rc_controls
    
    if not phone_addr:
        return
        
    try:
        import urllib.request
        import urllib.parse
        
        rc_json = rc_controls.to_json()
        data = urllib.parse.urlencode({'rc_controls': rc_json}).encode()
        
        req = urllib.request.Request(f'http://{phone_addr[0]}:8080/rc_controls', data=data)
        urllib.request.urlopen(req, timeout=5)
        print(f"🎮 RC: T={rc_controls.throttle:.2f} A={rc_controls.aileron:.2f} E={rc_controls.elevator:.2f} R={rc_controls.rudder:.2f} {'ARMED' if rc_controls.armed else 'DISARMED'}")
    except Exception as e:
        print(f"Error sending RC controls: {e}")

def send_command_to_phone(command):
    """Send custom command to phone via HTTP"""
    global phone_addr
    
    if not phone_addr:
        return
        
    try:
        import urllib.request
        import urllib.parse
        
        data = urllib.parse.urlencode({'command': command}).encode()
        
        req = urllib.request.Request(f'http://{phone_addr[0]}:8080/command', data=data)
        urllib.request.urlopen(req, timeout=5)
        print(f"✓ Sent command: {command}")
    except Exception as e:
        print(f"Error sending command: {e}")

def main():
    try:
        server = HTTPServer(('0.0.0.0', LISTEN_PORT), RCHTTPHandler)
        print(f"✓ RC Web Server listening on 0.0.0.0:{LISTEN_PORT} (HTTP)")
        
        print("\n" + "="*60)
        print("WEB-BASED RC PLANE CONTROL SERVER")
        print("="*60)
        print("Server is running on Render!")
        print("Access your RC control panel at the URL above")
        print("\nFeatures:")
        print("  - Web-based control interface")
        print("  - Real-time sliders for flight controls")
        print("  - Arm/Disarm buttons")
        print("  - Flight mode switching")
        print("  - Custom command sending")
        print("  - Telemetry display")
        print("="*60)
        
        # Start Xbox controller thread if available
        try:
            import inputs
            xbox_thread = threading.Thread(target=xbox_controller_loop, daemon=True)
            xbox_thread.start()
            print("✓ Xbox controller support enabled")
        except ImportError:
            print("⚠️  Xbox controller support not available (install: pip install inputs)")
        except Exception as e:
            print(f"⚠️  Xbox controller error: {e}")
        
        server.serve_forever()
        
    except Exception as e:
        print(f"Failed to start RC web server: {e}")
        print("Make sure port 8080 is not already in use.")
        input("Press Enter to exit...")

def xbox_controller_loop():
    """Handle Xbox controller input"""
    global rc_controls, phone_addr, is_connected
    
    try:
        import inputs
        
        # Xbox controller mappings
        devices = inputs.devices.gamepads
        if not devices:
            print("⚠️  No Xbox controller found")
            return
            
        print(f"✓ Found {len(devices)} gamepad(s)")
        
        while True:
            try:
                events = inputs.get_gamepad()
                for event in events:
                    if not is_connected or not phone_addr:
                        continue
                        
                    # Map Xbox controller to RC controls
                    if event.code == 'ABS_Y':  # Left stick Y (throttle)
                        rc_controls.throttle = (event.state + 32768) / 65536.0
                    elif event.code == 'ABS_X':  # Left stick X (rudder)
                        rc_controls.rudder = (event.state - 32768) / 32768.0
                    elif event.code == 'ABS_RY':  # Right stick Y (elevator)
                        rc_controls.elevator = (event.state - 32768) / 32768.0
                    elif event.code == 'ABS_RX':  # Right stick X (aileron)
                        rc_controls.aileron = (event.state - 32768) / 32768.0
                    elif event.code == 'BTN_SOUTH':  # A button (arm/disarm)
                        if event.state == 1:  # Button pressed
                            rc_controls.armed = not rc_controls.armed
                            print(f"🔄 {'ARMED' if rc_controls.armed else 'DISARMED'}")
                    elif event.code == 'BTN_EAST':  # B button (flight mode)
                        if event.state == 1:  # Button pressed
                            rc_controls.flight_mode = (rc_controls.flight_mode + 1) % 3
                            modes = ['MANUAL', 'STABILIZED', 'AUTO']
                            print(f"🔄 Flight mode: {modes[rc_controls.flight_mode]}")
                    
                    # Send RC controls to phone
                    send_rc_controls_to_phone()
                    
            except Exception as e:
                print(f"Xbox controller error: {e}")
                time.sleep(0.1)
                
    except Exception as e:
        print(f"Xbox controller setup failed: {e}")

if __name__ == "__main__":
    main()
