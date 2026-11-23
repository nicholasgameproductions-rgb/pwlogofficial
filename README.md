# PwLog 1.1 (redacted authentication activity)

This plugin logs only authentication command types and timestamps, with usernames,
UUIDs and IPs. **It never captures, prints, or writes plaintext passwords.**

## Build the JAR
1. Upload these files to the root of a GitHub repository (including `.github/workflows/build.yml`).
2. Open **Actions** > **Build PwLog** > **Run workflow**.
3. After the run succeeds, download the **PwLog** artifact from the workflow run; unzip to get `PwLog.jar`.
4. Install the JAR into the `plugins/` folder of a Paper 1.21.1 server (Java 21) and restart.

## Commands
- `/pwlog` or `/pwlog 2` — today's activity, paginated.
- `/pwlog search login` — filter today's entries.
- `/pwlog clear` — delete today's log.

Permission: `pwlog.view` (operators by default).
