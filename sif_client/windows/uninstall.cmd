@echo off
rem End the batch context before PowerShell deletes this launcher.
(goto) 2>nul & "%SystemRoot%\System32\WindowsPowerShell\v1.0\powershell.exe" -NoProfile -ExecutionPolicy Bypass -File "%~dp0uninstall.ps1" -InstallDirectory "%~dp0." %*
