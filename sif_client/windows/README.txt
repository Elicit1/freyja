SIF Client for Windows x64

This release includes the Python runtime inside sif.exe.

Install

1. Extract the entire ZIP into a directory.
2. Run install.cmd from CMD or PowerShell, or double-click it.
3. Close and reopen your terminal, then run sif --help.

The installer copies sif.exe to %LOCALAPPDATA%\Programs\SIF and adds that
directory to your user PATH. Installation uses your current Windows account.
To upgrade, run the installer from the newer release.

Uninstall

Run %LOCALAPPDATA%\Programs\SIF\uninstall.cmd from CMD or PowerShell.
The uninstaller removes the installed program and its user PATH entry.
Saved credentials are retained by default. To delete them during uninstall,
run uninstall.cmd -RemoveConfig. For a custom configuration file, also pass
-ConfigPath "C:\path\client.json". Close and reopen your terminal afterwards.
Other files in the installation directory are preserved.

Configure once

Create an API key in the SIF Server console, then run

  sif configure --base-url http://127.0.0.1:8011

Paste the key at the hidden prompt. Subsequent commands automatically read
%USERPROFILE%\.sif\config.json. This JSON file stores the key in plaintext,
with access controlled by the Windows user directory permissions.
Use the Server address reachable from this machine.

Generate

  sif capabilities
  sif readiness
  sif t2i --tier fast --prompt "A red teapot beside a sunlit window" --width 2048 --height 1152 --no-expand --output teapot.png
  sif i2i --tier fast --image teapot.png --prompt "Edit <Picture 1>. Change the teapot to blue." --width 2048 --height 1152 --no-expand --output edited.png
  sif queue
  sif jobs
  sif status JOB_UUID --output recovered.png
  sif config
  sif logout

The Client calls SIF Server over REST and automatically attaches the saved
API key. It uploads files, submits jobs, waits for events, and downloads images.
Use status to resume the existing job after a timeout or connection loss.
For a lost submission response, reuse the same --idempotency-key and parameters.

Portable use

You can keep the extracted sif.exe in a directory of your choice.
In PowerShell, run .\sif.exe --help. In CMD, run sif.exe --help.
Add that directory to PATH to use sif from other directories.

SIF Server also provides MCP at /mcp. Configure the MCP host with the Server
endpoint and its Bearer API key. CLI settings are used for REST calls.
