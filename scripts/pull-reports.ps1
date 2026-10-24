param(
    [string]$Serial,
    [string]$AdbPath,
    [string]$OutputDirectory = (Join-Path (Split-Path $PSScriptRoot -Parent) 'exports\reports'),
    [switch]$AllReports,
    [switch]$OpenFolder
)
# Windows PowerShell 5.1 / PowerShell 7. No phone files are modified.
$ErrorActionPreference = 'Stop'
$package = 'com.example.noiserecorder'

function Find-Adb {
    if ($AdbPath) { return (Get-Item -LiteralPath $AdbPath).FullName }
    $command = Get-Command adb.exe -ErrorAction SilentlyContinue
    if ($command) { return $command.Source }
    $sdkRoots = @($env:ANDROID_HOME, $env:ANDROID_SDK_ROOT, "$env:LOCALAPPDATA\Android\Sdk")
    $properties = Join-Path (Split-Path $PSScriptRoot -Parent) 'local.properties'
    if (Test-Path -LiteralPath $properties) {
        $line = Get-Content -LiteralPath $properties | Where-Object { $_ -match '^sdk\.dir=' } | Select-Object -First 1
        if ($line) { $sdkRoots += $line.Substring(8).Replace('\:', ':').Replace('\\', '\') }
    }
    foreach ($sdk in $sdkRoots) {
        if ($sdk -and (Test-Path -LiteralPath "$sdk\platform-tools\adb.exe")) { return "$sdk\platform-tools\adb.exe" }
    }
    throw 'adb.exe not found. Add platform-tools to PATH or pass -AdbPath <full path>.'
}

function Invoke-Adb([string[]]$Arguments) {
    # ADB may log normal daemon startup to stderr. Judge success by its exit code.
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $script:adb
    $info.Arguments = $Arguments -join ' '
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    try {
        [void]$process.Start()
        $errorTask = $process.StandardError.ReadToEndAsync()
        $output = $process.StandardOutput.ReadToEnd()
        $process.WaitForExit()
        $errorText = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "adb failed: $errorText $output" }
        return $output
    } finally { $process.Dispose() }
}

function Save-AdbStream([string[]]$Arguments, [string]$Destination) {
    # Never pipe binary through PowerShell redirection: Windows PowerShell corrupts it.
    $info = New-Object System.Diagnostics.ProcessStartInfo
    $info.FileName = $script:adb
    $info.Arguments = $Arguments -join ' ' # All tokens below are validated, space-free identifiers.
    $info.UseShellExecute = $false
    $info.CreateNoWindow = $true
    $info.RedirectStandardOutput = $true
    $info.RedirectStandardError = $true
    $process = New-Object System.Diagnostics.Process
    $process.StartInfo = $info
    $file = $null
    $started = $false
    try {
        [void]$process.Start()
        $started = $true
        $errorTask = $process.StandardError.ReadToEndAsync()
        $file = [IO.File]::Create($Destination)
        $process.StandardOutput.BaseStream.CopyTo($file)
        $file.Dispose(); $file = $null
        $process.WaitForExit()
        $errorText = $errorTask.GetAwaiter().GetResult()
        if ($process.ExitCode -ne 0) { throw "Report transfer failed: $errorText" }
    } finally {
        if ($file) { $file.Dispose() }
        if ($started -and !$process.HasExited) { $process.Kill(); $process.WaitForExit() }
        $process.Dispose()
    }
}

try {
    $script:adb = Find-Adb
    $tar = (Get-Command tar.exe -ErrorAction Stop).Source
    [void](Invoke-Adb @('start-server'))
    if (!$Serial) {
        # -d chooses a physical USB device, excluding emulators and TCP/IP devices.
        $Serial = (Invoke-Adb @('-d', 'get-serialno')).Trim()
    }
    if ($Serial -notmatch '^[A-Za-z0-9._:-]+$') { throw 'Invalid device serial.' }
    $deviceArgs = @('-s', $Serial)
    if ((Invoke-Adb ($deviceArgs + @('get-state'))).Trim() -ne 'device') { throw 'Device is not ready. Unlock it and authorize USB debugging.' }
    try { [void](Invoke-Adb ($deviceArgs + @('shell', 'run-as', $package, 'id'))) }
    catch { throw "Cannot read app-private reports. Install this project's debug APK (run-as required), then generate a report in the app. Details: $_" }

    try { $names = (Invoke-Adb ($deviceArgs + @('shell', 'run-as', $package, 'ls', '-1', 'files/reports'))) -split '\r?\n' }
    catch { throw "Cannot list reports. Generate a report in the app's Records tab first. Details: $_" }
    $ready = @(
        foreach ($name in $names) {
            if ($name -notmatch '^report_[0-9a-fA-F-]{36}$') { continue }
            try {
                $stamp = (Invoke-Adb ($deviceArgs + @('shell', 'run-as', $package, 'cat', "files/reports/$name/READY"))).Trim()
                if ($stamp -match '^\d+$') { [pscustomobject]@{ Name = $name; Generated = [long]$stamp } }
            } catch { Write-Warning "Skipping unfinished or unavailable report: $name" }
        }
    )
    if ($ready.Count -eq 0) { throw 'No completed reports. In the app: Records > select monitoring periods and generate report, then retry.' }
    $chosen = @($ready | Sort-Object Generated -Descending)
    if (!$AllReports) { $chosen = @($chosen[0]) }
    $folder = Join-Path ([IO.Path]::GetFullPath($OutputDirectory)) ("{0}_{1}" -f (Get-Date -Format 'yyyyMMdd_HHmmss'), [guid]::NewGuid().ToString('N').Substring(0,8))
    New-Item -ItemType Directory -Path $folder -Force | Out-Null
    $incomplete = Join-Path $folder 'INCOMPLETE.txt'
    Set-Content -LiteralPath $incomplete -Value 'Transfer incomplete. Do not use until verified. Rerun the script.'
    $archive = Join-Path $folder 'reports.tar'
    Write-Host "Device: $Serial | Reports: $($chosen.Count)"
    Write-Host 'Copying Word, HTML, charts and original report audio...'
    $reportNames = @($chosen | ForEach-Object { $_.Name })
    Save-AdbStream ($deviceArgs + @('exec-out', 'run-as', $package, 'tar', '-C', 'files/reports', '-cf', '-') + $reportNames) $archive

    $entries = @(& $tar -tf $archive)
    if ($LASTEXITCODE -ne 0) { throw 'Invalid or incomplete report archive.' }
    foreach ($entry in $entries) {
        $parts = $entry.TrimEnd('/') -split '/'
        if ($parts[0] -notin $reportNames -or $parts -contains '..' -or $parts -contains '.' -or $entry -match '[\\:]' -or $entry.StartsWith('/')) { throw "Unsafe archive path: $entry" }
    }
    & $tar -xf $archive -C $folder
    if ($LASTEXITCODE -ne 0) { throw 'Report extraction failed.' }
    $audioCount = 0
    foreach ($name in $reportNames) {
        $report = Join-Path $folder $name
        foreach ($required in @('READY', 'report.docx', 'report.html', 'SHA256SUMS.txt', 'README.txt')) {
            if (!(Test-Path -LiteralPath (Join-Path $report $required) -PathType Leaf)) { throw "Missing report file: $name/$required" }
        }
        foreach ($line in (Get-Content -LiteralPath (Join-Path $report 'SHA256SUMS.txt'))) {
            if ([string]::IsNullOrWhiteSpace($line)) { continue }
            if ($line -notmatch '^([0-9a-fA-F]{64})  (audio/[A-Za-z0-9_.-]+\.wav)$') { throw 'Invalid audio checksum manifest.' }
            $expected = $Matches[1]; $relative = $Matches[2]
            $actual = (Get-FileHash -LiteralPath (Join-Path $report $relative) -Algorithm SHA256).Hash
            if ($actual -ne $expected) { throw "Audio checksum mismatch: $name/$relative" }
            $audioCount++
        }
    }
    Remove-Item -LiteralPath $archive
    Remove-Item -LiteralPath $incomplete
    Write-Host "Success: $($chosen.Count) report(s), $audioCount audio file(s) verified."
    Write-Host "Saved to: $folder"
    Write-Host 'Open report.docx in Word, or report.html in a browser. Keep audio and chart files beside HTML.'
    if ($OpenFolder) {
        $openTarget = if ($reportNames.Count -eq 1) { Join-Path $folder $reportNames[0] } else { $folder }
        Start-Process explorer.exe -ArgumentList ('"{0}"' -f $openTarget)
    }
} catch {
    Write-Host "ERROR: $_" -ForegroundColor Red
    if ($folder) { Write-Host "Partial files (marked INCOMPLETE): $folder" }
    exit 1
}
