<#
  Capture one IntelliJ Settings dialog as a whole window and frame it.

  capture.py stages the dialog in the sandbox, in-process, and runs this with the dialog's window
  handle. The shutter's Screen method reads the composited window, so the capture is cut to DWM's
  own frame rect with the real rounded corners, and Add-WindowFrame keeps that capture as
  docs/screenshots/raw/<name> before drawing the frame in place.

  This takes over the machine for a second or two: the shutter presses Alt, raises the dialog and
  reads the screen. The pointer is parked clear of the window during the shot and put back after.
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][int]$Hwnd,
    [Parameter(Mandatory = $true)][string]$Out,
    [Parameter(Mandatory = $true)][int]$ParkX,
    [Parameter(Mandatory = $true)][int]$ParkY
)
$ErrorActionPreference = 'Stop'
. (Join-Path $env:USERPROFILE '.claude\skills\docs-relevance\scripts\windows-capture.ps1')

Invoke-WithPointerAt -X $ParkX -Y $ParkY -Do { Invoke-WindowShot @{ Hwnd = $Hwnd; Method = 'Screen'; Out = $Out } }
Add-WindowFrame -Path $Out
