$ErrorActionPreference = "Stop"
$Here = Split-Path -Parent $MyInvocation.MyCommand.Path
$AndroidDir = Split-Path -Parent $Here
$Target = Join-Path $AndroidDir "voice-sip-agent"
$Files = Join-Path $Here "files"
$Pin = "5105a1f447ffa25ba32d0620dbeef44bbc1705ce"
if (!(Test-Path (Join-Path $Target ".git"))) { throw "Submodule missing. Run: git submodule update --init --recursive" }
$Actual = (git -C $Target rev-parse HEAD).Trim()
if ($Actual -ne $Pin) { throw "Unexpected upstream revision: $Actual (expected $Pin)" }
Get-ChildItem -Path $Files -Recurse -File | ForEach-Object {
    $Relative = $_.FullName.Substring($Files.Length).TrimStart([IO.Path]::DirectorySeparatorChar)
    $Destination = Join-Path $Target $Relative
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $Destination) | Out-Null
    Copy-Item $_.FullName $Destination -Force
}
Write-Host "Gemini Live overlay applied to pinned Voice SIP Agent $Pin"
