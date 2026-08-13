const http = require("http");
const express = require("express");
const { Server } = require("socket.io");
const { JWT } = require("google-auth-library");
const fs = require("fs");
const path = require("path");
const dns = require("dns");

dns.setDefaultResultOrder("ipv4first");
/* ================= LOGGER ================= */
const POLICY_FILE = path.join(__dirname, "pending_policies.json");
if (!fs.existsSync(POLICY_FILE)) fs.writeFileSync(POLICY_FILE, "{}");

function readPolicies() {
  return JSON.parse(fs.readFileSync(POLICY_FILE, "utf8"));
}

function savePolicies(data) {
  fs.writeFileSync(POLICY_FILE, JSON.stringify(data, null, 2));
}

// Simple duplicate checker (NO crypto)
function isSamePolicy(a, b) {
  return JSON.stringify(a) === JSON.stringify(b);
}

const LOG_FILE = path.join(__dirname, "server.log");
function log(msg) {
  const line = `[${new Date().toISOString()}] ${msg}\n`;
  fs.appendFileSync(LOG_FILE, line);
  console.log(line.trim());
}

/* ================= FIREBASE HTTP v1 (JWT) ================= */

const serviceAccount = require("./hmdm-2e5f2-firebase-adminsdk-fbsvc-dcefa57205.json");
const PROJECT_ID = serviceAccount.project_id;

const jwtClient = new JWT({
  email: serviceAccount.client_email,
  key: serviceAccount.private_key,
  scopes: ["https://www.googleapis.com/auth/firebase.messaging"],
});

let cachedAccessToken = null;
let tokenExpiry = null;

async function getAccessToken() {
  if (cachedAccessToken && tokenExpiry && Date.now() < tokenExpiry) {
    return cachedAccessToken;
  }
  try {
    const tokens = await jwtClient.authorize();
    cachedAccessToken = tokens.access_token;
    // Buffer of 10 seconds before actual expiry
    tokenExpiry = tokens.expiry_date - 10000;
    return cachedAccessToken;
  } catch (error) {
    console.error("❌ Error getting access token:", error);
    throw error;
  }
}

async function sendFCMMessage(messagePayload) {
  const token = await getAccessToken();
  const fcmUrl = `https://fcm.googleapis.com/v1/projects/${PROJECT_ID}/messages:send`;
  
  const response = await fetch(fcmUrl, {
    method: "POST",
    headers: {
      "Authorization": `Bearer ${token}`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({ message: messagePayload }),
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(JSON.stringify(data));
  }
  return data;
}

/* ================= TOKEN STORAGE ================= */

const TOKEN_FILE = path.join(__dirname, "fcm_tokens.json");
if (!fs.existsSync(TOKEN_FILE)) fs.writeFileSync(TOKEN_FILE, "{}");

function readTokens() {
  return JSON.parse(fs.readFileSync(TOKEN_FILE, "utf8"));
}
function saveToken(imei, token) {
  const data = readTokens();
  data[imei] = token; // replace if exists
  fs.writeFileSync(TOKEN_FILE, JSON.stringify(data, null, 2));
}

/* ================= APP ================= */

const app = express();
const server = http.createServer(app);
app.use(express.json());

/* ================= SOCKET ================= */

const io = new Server(server, {
  path: "/socket.io",
  cors: { origin: "*", methods: ["GET", "POST"] },
  transports: ["polling", "websocket"],
  allowEIO3: true,
});

// IMEI -> socket.id
const connectedDevices = new Map();

/* ================= SEND STORED POLICIES WHEN DEVICE CONNECTS ================= */

io.on("connection", (socket) => {
  log(`✅ Socket connected ${socket.id}`);
  broadcastList();

  socket.on("register", async (imei) => {
    if (!imei) return;

    socket.join(imei);
    connectedDevices.set(imei, socket.id);
    await notifyDeviceStatus(imei, true);
    log(`📱 Device registered IMEI=${imei}`);
    broadcastList();

    // 🔥 Send stored policies
    const policies = readPolicies();
    if (policies[imei]) {
      policies[imei].forEach(p => {
        io.to(imei).emit("policy_update", {
          imei,
          data: p.data,
          command_id: p.command_id,
          offlineStored: true
        });
        log(`📤 Sent stored policy IMEI=${imei}`);
      });

      // Remove after sending
      delete policies[imei];
      savePolicies(policies);
    }
  });

  socket.on("disconnect", async () => {
    for (const [imei, sid] of connectedDevices.entries()) {
      if (sid === socket.id) {
        connectedDevices.delete(imei);
        log(`📴 Device offline IMEI=${imei}`);
        await notifyDeviceStatus(imei, false);
        break;
      }
    }
    broadcastList();
  });
});

/* ================= ACK ================= */

const DEVICE_STATUS_API = "https://ailocker.org/api2/DeviceActive.php";

async function notifyDeviceStatus(imei, status) {
  try {
    await fetch(DEVICE_STATUS_API, {
      method: "POST",
      headers: {
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        imei: imei,
        status: status,
      }),
    });

    console.log("📡 Device status sent:", imei, status);
  } catch (err) {
    console.error("❌ Failed to notify device status:", err.message);
  }
}

/* ================= 🔥 MERGED LIST BROADCAST ================= */

function broadcastList() {
  const tokens = readTokens();
  const devicesMap = {};

  // 1️⃣ Add all token devices (OFFLINE by default)
  Object.keys(tokens).forEach(imei => {
    devicesMap[imei] = {
      imei,
      token: tokens[imei],
      online: false,
    };
  });

  // 2️⃣ Mark socket-connected devices as ONLINE
  connectedDevices.forEach((_, imei) => {
    devicesMap[imei] = {
      imei,
      token: tokens[imei] || "-",
      online: true,
    };
  });

  io.emit("list_update", {
    total: Object.keys(devicesMap).length,
    devices: Object.values(devicesMap),
  });
}

// 🔁 Check remove-device list every 30 seconds.
// REMOVE_DEVICE_API was previously an undefined variable → the fetch threw
// "REMOVE_DEVICE_API is not defined" on every tick (caught, but spamming the log
// and never actually polling). It is now opt-in via env var: the poll only runs
// when REMOVE_DEVICE_API is configured.
const REMOVE_DEVICE_API = process.env.REMOVE_DEVICE_API || null;

if (REMOVE_DEVICE_API) {
  log(`🔁 Remove-device polling enabled → ${REMOVE_DEVICE_API}`);
  setInterval(() => {
    checkAndRemoveDevices();
  }, 30 * 1000);
} else {
  log("ℹ️ REMOVE_DEVICE_API not set → remove-device polling disabled");
}

async function checkAndRemoveDevices() {
  if (!REMOVE_DEVICE_API) return;
  try {
    const res = await fetch(REMOVE_DEVICE_API);
    const data = await res.json();

    if (!data.status || !Array.isArray(data.imeis)) {
      return;
    }

    log(`🧹 Remove-device check: ${data.imeis.length} IMEI(s)`);

    data.imeis.forEach((imei) => {
      if (connectedDevices.has(imei)) {
        io.to(imei).emit("remove_device", {
          imei,
          time: Date.now(),
        });

        log(`❌ remove_device sent via socket IMEI=${imei}`);
      } else {
        log(`⚠️ IMEI offline, cannot send remove_device: ${imei}`);
      }
    });
  } catch (err) {
    log(`🔥 Remove-device API error: ${err.message}`);
  }
}

/* ❌ REMOVE DEVICE (SINGLE IMEI) */
app.post("/remove-device", async (req, res) => {
  const { imei } = req.body;

  if (!imei) {
    return res.status(400).json({
      status: false,
      message: "IMEI is required",
    });
  }

  let socketSent = false;
  let pushSent = false;

  if (connectedDevices.has(imei)) {
    io.to(imei).emit("remove_device", {
      imei,
      time: Date.now(),
      source: "manual_api",
    });

    socketSent = true;
    log(`❌ remove_device sent via POST API IMEI=${imei}`);
  } else {
    log(`⚠️ remove_device requested but IMEI offline: ${imei}`);
  }
  
  const token = readTokens()[imei];
  if (token) {
    pushSent = await sendSilentPush(token, {
      type: "REMOVE_MOBILE",
      imei,
      time: Date.now().toString(),
    });
  }

  res.json({
    status: true,
    imei,
    socket: socketSent,
  });
});

/* ================= FIREBASE PUSH LOGIC (HTTP v1) ================= */

async function sendPush(token, title, body, data = {}) {
  try {
    const message = {
      token: token,
      data: data,
      android: { priority: "high" },
    };

    if (title || body) {
      message.notification = {};
      if (title) message.notification.title = title;
      if (body) message.notification.body = body;
    }

    await sendFCMMessage(message);
    log(`🔔 Push sent ${token.substring(0, 12)}...`);
    return true;
  } catch (err) {
    log(`🔥 FCM error ${err.message}`);
    return false;
  }
}

async function sendSilentPush(token, data = {}) {
  try {
    const message = {
      token: token,
      data: {
        type: "ACK",
        ...data,
      },
      android: { priority: "high" },
    };

    await sendFCMMessage(message);
    log(`📨 Silent push sent ${token.substring(0, 12)}...`);
    return true;
  } catch (err) {
    log(`🔥 Silent FCM error ${err.message}`);
    return false;
  }
}

/* ================= API ================= */

app.get("/", (req, res) => {
  res.json({ status: true, message: "Socket + FCM server running via HTTP v1" });
});

/* 🔹 SAVE TOKEN */
app.post("/save-fcm-token", (req, res) => {
  const { imei, fcmToken } = req.body;
  if (!imei || !fcmToken) return res.status(400).json({ status: false });

  saveToken(imei, fcmToken);
  log(`🔐 Token saved IMEI=${imei}`);
  broadcastList();

  res.json({ status: true });
});

app.get("/test", async (req, res) => {
  try {
    const result = {
      status: true,
      serverTime: new Date().toISOString(),
      nodeVersion: process.version,
      authLibraryLoaded: true,
      accessTokenGenerated: false,
      project_id: PROJECT_ID,
      client_email: serviceAccount.client_email,
      errors: []
    };

    try {
      const token = await getAccessToken();
      result.accessTokenGenerated = true;
      result.tokenType = "Bearer";
    } catch (err) {
      result.errors.push({
        type: "google_auth",
        message: err.message,
        stack: err.stack
      });
    }

    return res.json(result);
  } catch (err) {
    return res.status(500).json({
      status: false,
      message: err.message,
      stack: err.stack
    });
  }
});

app.get("/oauth-test", async (req, res) => {
  try {
    const token = await getAccessToken();
    return res.json({
      status: true,
      token: {
        access_token: token
      }
    });
  } catch (err) {
    return res.json({
      status: false,
      message: err.message,
      code: err.code,
      errno: err.errno,
      type: err.type,
      syscall: err.syscall,
      hostname: err.hostname,
      response: err.response?.data || null,
      cause: err.cause || null,
      stack: err.stack
    });
  }
});

app.get("/dns-test", async (req, res) => {
  const dns = require("dns");

  dns.lookup("oauth2.googleapis.com", { all: true }, (err, addresses) => {
    res.json({
      err,
      addresses
    });
  });
});

/* ================= PENDING POLICY STORAGE ================= */

app.post("/changePolicy", async (req, res) => {
  const { imei, data, command_id } = req.body;
  if (!imei || !data) return res.status(400).json({ status: false });

  let socketSent = false;
  let pushSent = false;

  const policies = readPolicies();

  // Create IMEI array if not exists
  if (!policies[imei]) policies[imei] = [];

  // Check duplicate policy
  const exists = policies[imei].some(p => isSamePolicy(p.data, data));

  if (!exists) {
    policies[imei].push({
      data,
      command_id,
      createdAt: Date.now()
    });
    savePolicies(policies);
    log(`💾 Policy stored for IMEI=${imei}`);
  } else {
    log(`⚠️ Duplicate policy ignored IMEI=${imei}`);
  }

  // Send via socket if online
  if (connectedDevices.has(imei)) {
    io.to(imei).emit("policy_update", { imei, data, command_id });
    socketSent = true;
  }

  // Send FCM fallback
  const token = readTokens()[imei];
  if (token) {
    pushSent = await sendPush(
      token,
      null,
      null,
      { imei, payload: JSON.stringify(data), commandId: JSON.stringify(command_id) }
    );
  }

  res.json({ status: true, socket: true, push: pushSent });
});

/* 🔄 REBOOT DEVICE */
app.post("/rebootDevice", async (req, res) => {
  const { imei } = req.body;

  if (!imei) {
    return res.status(400).json({
      status: false,
      message: "IMEI is required",
    });
  }

  let socketSent = false;
  let pushSent = false;

  // 1️⃣ Send via Socket if device is ONLINE
  if (connectedDevices.has(imei)) {
    io.to(imei).emit("REBOOT_DEVICE", {
      imei,
      time: Date.now(),
    });

    socketSent = true;
    log(`🔄 REBOOT_DEVICE sent via socket IMEI=${imei}`);
  } else {
    log(`⚠️ Device offline for reboot IMEI=${imei}`);
  }

  // 2️⃣ FCM fallback (for OFFLINE or backup)
  const token = readTokens()[imei];
  if (token) {
    pushSent = await sendSilentPush(token, {
      type: "REBOOT_DEVICE",
      imei,
      time: Date.now().toString(),
    });
  }

  return res.json({
    status: true,
    imei,
    socket: socketSent,
    push: pushSent,
  });
});

/* 📍 FETCH GPS */
app.post("/fetch-gps", async (req, res) => {
  const { imei } = req.body;
  if (!imei) {
    return res.status(400).json({
      status: false,
      message: "IMEI required",
    });
  }

  let socketSent = false;
  let pushSent = false;

  // 1️⃣ Send via Socket if device is online
  if (connectedDevices.has(imei)) {
    io.to(imei).emit("FETCH_GPS", {
      imei,
      time: Date.now(),
    });
    socketSent = true;
    log(`📡 FETCH_GPS sent via socket IMEI=${imei}`);
  }

  // 2️⃣ Send via FCM if token exists (offline or fallback)
  const token = readTokens()[imei];
  if (token) {
    pushSent = await sendSilentPush(token, {
      type: "FETCH_GPS",
      imei,
      time: Date.now().toString(),
    });
  }

  res.json({
    status: true,
    socket: socketSent,
    push: pushSent,
  });
});

app.get("/https-test", (req, res) => {
  const https = require("https");

  const request = https.get(
    "https://oauth2.googleapis.com/token",
    (response) => {
      res.json({
        status: true,
        code: response.statusCode,
        headers: response.headers
      });
    }
  );

  request.setTimeout(10000, () => {
    request.destroy();
    res.json({
      status: false,
      error: "TIMEOUT"
    });
  });

  request.on("error", (err) => {
    res.json({
      status: false,
      error: err.message,
      code: err.code
    });
  });
});

app.get("/google-test", async (req, res) => {
  try {
    const response = await fetch("https://www.google.com");

    res.json({
      status: true,
      code: response.status
    });
  } catch (e) {
    res.json({
      status: false,
      message: e.message,
      code: e.code
    });
  }
});

app.get("/token-post-test", async (req, res) => {
  try {
    const response = await fetch(
      "https://oauth2.googleapis.com/token",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded"
        },
        body: "grant_type=client_credentials"
      }
    );

    const text = await response.text();

    res.json({
      status: true,
      code: response.status,
      body: text
    });

  } catch (e) {
    res.json({
      status: false,
      message: e.message,
      code: e.code,
      cause: e.cause,
      stack: e.stack
    });
  }
});

app.get("/oauth-ip-test", async (req, res) => {
  try {
    const response = await fetch(
      "https://oauth2.googleapis.com/.well-known/openid-configuration"
    );

    const text = await response.text();

    res.json({
      status: true,
      code: response.status,
      length: text.length
    });
  } catch (e) {
    res.json({
      status: false,
      message: e.message,
      code: e.code,
      cause: e.cause
    });
  }
});

app.get("/fetch-test", async (req, res) => {
  try {
    const response = await fetch(
      "https://oauth2.googleapis.com/token",
      {
        method: "POST",
        headers: {
          "Content-Type": "application/x-www-form-urlencoded"
        },
        body: "grant_type=client_credentials"
      }
    );

    const text = await response.text();

    res.json({
      status: true,
      code: response.status,
      body: text
    });
  } catch (e) {
    res.json({
      status: false,
      message: e.message,
      code: e.code,
      cause: e.cause,
      stack: e.stack
    });
  }
});

app.get("/https-native-test", (req, res) => {
  const https = require("https");

  const reqHttps = https.get(
    "https://oauth2.googleapis.com/token",
    (r) => {
      res.json({
        status: true,
        code: r.statusCode
      });
    }
  );

  reqHttps.setTimeout(15000);

  reqHttps.on("error", (e) => {
    res.json({
      status: false,
      message: e.message,
      code: e.code
    });
  });
});

app.get("/googleapis-test", async (req, res) => {
  try {
    const response = await fetch("https://www.googleapis.com");

    res.json({
      status: true,
      code: response.status
    });
  } catch (e) {
    res.json({
      status: false,
      message: e.message,
      code: e.code,
      cause: e.cause
    });
  }
});

/* ♿ DISABLE ACCESSIBILITY API (FCM ONLY) */
app.post("/disable-accessibility", async (req, res) => {
  const { imei } = req.body;

  if (!imei) {
    return res.status(400).json({
      status: false,
      message: "IMEI is required",
    });
  }

  let pushSent = false;

  // Send via FCM if token exists
  const token = readTokens()[imei];
  if (token) {
    pushSent = await sendSilentPush(token, {
      type: "DISABLE_ACCESSIBILITY",
      imei,
      time: Date.now().toString(),
    });
  } else {
    log(`❌ No FCM token found for IMEI=${imei}`);
  }

  res.json({
    status: true,
    imei,
    push: pushSent,
  });
});

/* ❌ REMOVE MOBILE COMMAND API */
app.post("/removeMobile", async (req, res) => {
  const { imei } = req.body;

  if (!imei) {
    return res.status(400).json({
      status: false,
      message: "IMEI is required",
    });
  }

  let socketSent = false;
  let pushSent = false;

  // Check if device is online
  if (connectedDevices.has(imei)) {
    io.to(imei).emit("remove_mobile", {
      imei,
      time: Date.now(),
      source: "removeMobile_api",
    });

    socketSent = true;
    log(`📵 remove_mobile sent via API IMEI=${imei}`);
  } else {
    log(`⚠️ remove_mobile requested but IMEI offline: ${imei}`);
  }

  // 2️⃣ FCM fallback (for OFFLINE device)
  const token = readTokens()[imei];
  if (token) {
    pushSent = await sendSilentPush(token, {
      type: "REMOVE_MOBILE",
      imei,
      time: Date.now().toString(),
    });
  }

  res.json({
    status: true,
    imei,
    socket: socketSent,
  });
});

/* 🔔 AUDIO REMINDER */
app.post("/audio-reminder", async (req, res) => {
  const { imei } = req.body;

  if (!imei) {
    return res.status(400).json({
      status: false,
      message: "IMEI is required",
    });
  }

  let socketSent = false;
  let pushSent = false;

  // 1️⃣ Send via Socket if device is ONLINE
  if (connectedDevices.has(imei)) {
    io.to(imei).emit("audio_reminder", {
      imei,
      time: Date.now(),
    });

    socketSent = true;
    log(`🔊 audio_reminder sent via socket IMEI=${imei}`);
  } else {
    log(`⚠️ audio_reminder: IMEI offline ${imei}`);
  }

  // 2️⃣ Optional: FCM fallback if device OFFLINE
  const token = readTokens()[imei];
  if (!socketSent && token) {
    pushSent = await sendSilentPush(token, {
      type: "AUDIO_REMINDER",
      imei,
      time: Date.now().toString(),
    });
  }

  res.json({
    status: true,
    imei,
    socket: socketSent,
    push: pushSent,
  });
});

/* 🔔 SEND FCM NOTIFICATION ONLY */
app.post("/sendNotification", async (req, res) => {
  const { imei, title, message: bodyMessage } = req.body;

  if (!imei || !title || !bodyMessage) {
    return res.status(400).json({
      status: false,
      message: "imei, title and message are required",
    });
  }

  const tokens = readTokens();
  const token = tokens[imei];

  if (!token) {
    log(`❌ No FCM token found for IMEI=${imei}`);
    return res.status(404).json({
      status: false,
      message: "FCM token not found for this IMEI",
    });
  }

  try {
    const fcmMessage = {
      token: token,
      notification: {
        title: title,
        body: bodyMessage,
      },
      android: {
        priority: "high",
      },
    };

    await sendFCMMessage(fcmMessage);
    
    log(`🔔 Notification sent IMEI=${imei}`);
    return res.json({
      status: true,
      imei,
      sent: true,
    });
  } catch (err) {
    log(`🔥 Notification error IMEI=${imei} | ${err.message}`);
    return res.status(500).json({
      status: false,
      message: "Failed to send notification",
      error: err.message,
    });
  }
});

/* ================= /LIST PAGE (MERGED VIEW) ================= */

app.get("/list", (req, res) => {
  res.send(`
<!DOCTYPE html>
<html>
<head>
  <title>Device Monitor</title>
  <script src="/socket.io/socket.io.js"></script>
  <style>
    body { font-family: Arial; background: #111; color: #fff; }
    table { width: 100%; border-collapse: collapse; margin-top: 15px; }
    th, td { border: 1px solid #444; padding: 8px; }
    th { background: #222; }
    .online { color: #00ff88; }
    .offline { color: #ff5555; }
  </style>
</head>
<body>

<h2>📡 Live Connected Devices</h2>
<p>Total Connected: <b id="total">0</b></p>

<table>
  <thead>
    <tr>
      <th>IMEI</th>
      <th>FCM Token</th>
      <th>Status</th>
    </tr>
  </thead>
  <tbody id="list"></tbody>
</table>

<script>
  const socket = io({
    path: "/socket.io",
    transports: ["polling","websocket"]
  });

  socket.on("list_update", data => {
    document.getElementById("total").innerText = data.total;
    const tbody = document.getElementById("list");
    tbody.innerHTML = "";

    data.devices.forEach(d => {
      tbody.innerHTML += \`
        <tr>
          <td>\${d.imei}</td>
          <td>\${d.token === "-" ? "-" : d.token.substring(0, 25) + "..."}</td>
          <td class="\${d.online ? "online" : "offline"}">
            \${d.online ? "ONLINE" : "OFFLINE"}
          </td>
        </tr>
      \`;
    });
  });
</script>

</body>
</html>
`);
});

setInterval(() => {
  const policies = readPolicies();
  const FOUR_DAYS = 4 * 24 * 60 * 60 * 1000;
  const now = Date.now();

  for (const imei in policies) {
    policies[imei] = policies[imei].filter(p => (now - p.createdAt) < FOUR_DAYS);

    if (policies[imei].length === 0) delete policies[imei];
  }

  savePolicies(policies);
  log("🧹 Old policies cleaned");
}, 60 * 60 * 1000); // every 1 hour

/* ================= SERVER ================= */

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => log(`🚀 Server running on port ${PORT}`));