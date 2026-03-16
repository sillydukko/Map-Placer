# MapAutoPlace — Meteor Client Addon

Auto-places maps on empty item frames near you. Built for **Minecraft 1.21.1 + Meteor Client**.

---

## Settings

| Setting | Type | Default | Description |
|---|---|---|---|
| `auto-place` | Bool | on | Master toggle |
| `stack-frames` | Bool | off | Break a filled frame first, then re-place a new map on it (saves frames!) |
| `chunk-distance` | 0.5–16 | 2.0 chunks | Min chunk-distance between placements — stops it dumping all your maps at once |

**How it works:**
- Listens for `EntitySpawnS2CPacket` — freshly spawned item frames are queued instantly
- Also scans all item frames within **32 blocks** every game tick
- Finds a blank map in your hotbar/inventory and right-clicks the frame
- Chunk-distance throttle prevents spam; history auto-clears after 5 minutes

---

## Getting the JAR (no local Java needed — free GitHub Actions build)

### Step 1 — Upload to GitHub
1. Create a free account at https://github.com
2. Click **+** → **New repository** → name it anything → **Create repository**
3. On the next screen click **"uploading an existing file"**
4. Drag ALL files/folders from this zip into the upload box (make sure `.github/` folder is included)
5. Click **Commit changes**

### Step 2 — Wait ~3 minutes
- GitHub auto-runs the **Build JAR** workflow on every push
- Click the **Actions** tab → wait for the green tick

### Step 3 — Download your JAR
- Click the finished run → scroll to **Artifacts** → download **`map-auto-place-jar`**
- Extract the zip inside → grab `map-auto-place-1.0.0.jar`

### Step 4 — Install
1. Drop the jar into `.minecraft/mods/`
2. Make sure Meteor Client + Fabric API are also in mods/
3. Launch Fabric 1.21.1

---

## Manual build (Java 21 required)
```bash
./gradlew build
# Output: build/libs/map-auto-place-1.0.0.jar
```

## License
GPL-3.0 (required by Meteor Client's licence)
