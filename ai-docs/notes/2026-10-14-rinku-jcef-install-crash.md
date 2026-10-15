Rinku's own JCEF installer failed on Windows (`AccessDeniedException` publishing its staging move), which left Rinku uninitialized; opening the browser screen then attempted a CEF startup with no installation, and that is what halted and killed the client (exit NTSTATUS 0xCFFFFFFF, no hs_err). The mod now refuses to open the screen while the install is in that state and says how to retry.

**Symptom** (user run, 2026-10-14): first `runClient` launch, build staged fine, then:

```
[Rinku-Downloader/WARN] (RinkuDownloader) JCEF release validation failed for https://github.com; trying the next permitted mirror if available
[Rinku-Downloader/ERROR] (Minecraft) Failed to initialize JCEF downloader
java.nio.file.AccessDeniedException: E:\Coding_Projects\craftraria\run\rinku-libraries\jcef-v1\windows_amd64\.staging\...-...\extracted\windows_amd64 -> E:\Coding_Projects\craftraria\run\rinku-libraries\jcef-v1\windows_amd64\2eb4ca2...
```

From then on Rinku's own `MixinGui` logged `Rinku failed to initialize!` on every screen change, `Rinku.isInitialized()` stayed false. Pressing `B` opened `BrowserScreen`, whose wait-for-init path called `Rinku.scheduleForInit`; Rinku then attempted to start CEF with no published library directory, and the process aborted with `NTSTATUS 0xCFFFFFFF` about a second after the screen opened. No `hs_err` file was written, which fits a native CHECK-failure abort rather than a JVM crash.

**What was wrong in the mod**: it could open the screen with Chromium known-unavailable. `BrowserScreen` scheduled an init that could not succeed, and the client had no gate on Rinku's install state at all, so the failure path was reachable by pressing one key.

**Fix** (2026-10-14, `CraftrariaClient.isChromiumAvailable` + `BrowserScreen.onRinkuInitFailed`):

- `openBrowserIfReady` checks `RinkuDownloadListener.INSTANCE.isFailed()` — a public class in the Rinku jar — before opening the screen. A **failed** install refuses the keypress with a message naming the retry; a **download still in progress** still opens the screen and lets Rinku's own flow finish, as before.
- `BrowserScreen.createBrowserIfNotReady`'s `onInit(false)` branch now closes the screen (`minecraft.gui.setScreen(null)`, the 26.2 spelling — `Minecraft.setScreen` no longer exists) and logs the same remedy, so a screen that fails to acquire a browser never lingers.

**Why it matters**: this is the one crash class that costs the whole client, from a keypress, on the most common first launch (the JCEF install is a one-time ~190 MB step every player performs). It also proves the value of gating on the dependency's public state instead of trusting the intended flow: the ported screen was correct in the happy path only.

**Player remedy, in order**:
1. Delete `<instance>/run/rinku-libraries` and launch again — the installer re-extracts, and the lock that caused the `AccessDeniedException` (usually a virus scanner scanning the freshly written files) is transient.
2. If it recurs, add a scanner exclusion for the run directory; nothing in the mod can work around a scanner that locks files mid-install.
3. Do not delete `run/config` — that holds the recorded Content path.

**Open question**: the exact native abort inside Rinku's failed-start path was not identified, because it produced no crash log and the binary is third-party. The mod-side guard makes that path unreachable from a keypress, which is the change that matters; if a future Rinku version init-fails some other way, `onRinkuInitFailed` is the second net.
