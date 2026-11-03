$ErrorActionPreference = 'Stop'

$databaseName = 'barbershop_db'
$backupFolder = Join-Path $env:USERPROFILE 'Documents\BARBER HOUSE Backups'
$oneDriveRoot = $env:OneDrive
$logFile = Join-Path $backupFolder 'backup.log'
$timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
$archiveName = "$databaseName-$timestamp.dump"
$archivePath = Join-Path $backupFolder $archiveName
$partialPath = "$archivePath.partial"
$pgInstallations = Get-ChildItem 'C:\Program Files\PostgreSQL' -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'bin\pg_dump.exe') } |
    Sort-Object { try { [version]$_.Name } catch { [version]'0.0' } } -Descending

if (-not $pgInstallations) {
    throw 'PostgreSQL command line tools were not found under C:\Program Files\PostgreSQL.'
}

$pgBin = Join-Path $pgInstallations[0].FullName 'bin'
$pgDump = Join-Path $pgBin 'pg_dump.exe'
$pgRestore = Join-Path $pgBin 'pg_restore.exe'

New-Item -ItemType Directory -Path $backupFolder -Force | Out-Null

try {
    & $pgDump --host 127.0.0.1 --port 5432 --username postgres --dbname $databaseName `
        --format custom --no-owner --no-acl --no-password --file $partialPath
    if ($LASTEXITCODE -ne 0) {
        throw "pg_dump failed with exit code $LASTEXITCODE. Confirm PostgreSQL is running and local backup access is configured."
    }

    $archiveEntries = & $pgRestore --list $partialPath 2>$null
    if ($LASTEXITCODE -ne 0 -or -not $archiveEntries) {
        throw 'The PostgreSQL archive failed its integrity check.'
    }

    Move-Item -LiteralPath $partialPath -Destination $archivePath
    $checksum = (Get-FileHash -Algorithm SHA256 -LiteralPath $archivePath).Hash
    Set-Content -LiteralPath "$archivePath.sha256" -Value $checksum -NoNewline -Encoding ascii

    $oneDriveCopyVerified = $false
    if (-not [string]::IsNullOrWhiteSpace($oneDriveRoot) -and (Test-Path -LiteralPath $oneDriveRoot)) {
        $oneDriveFolder = Join-Path $oneDriveRoot 'BARBER HOUSE Backups'
        New-Item -ItemType Directory -Path $oneDriveFolder -Force | Out-Null
        $cloudArchive = Join-Path $oneDriveFolder $archiveName
        Copy-Item -LiteralPath $archivePath -Destination $cloudArchive -Force
        Copy-Item -LiteralPath "$archivePath.sha256" -Destination "$cloudArchive.sha256" -Force
        $oneDriveCopyVerified = (Get-FileHash -Algorithm SHA256 -LiteralPath $cloudArchive).Hash -eq $checksum
        if (-not $oneDriveCopyVerified) {
            throw 'The OneDrive copy did not match the local archive checksum.'
        }
    }

    $retentionCutoff = (Get-Date).AddDays(-30)
    $foldersToPrune = @($backupFolder)
    if ($oneDriveCopyVerified) {
        $foldersToPrune += $oneDriveFolder
    }
    foreach ($folder in $foldersToPrune) {
        $resolvedFolder = [System.IO.Path]::GetFullPath($folder)
        foreach ($oldArchive in Get-ChildItem -LiteralPath $resolvedFolder -File -Filter "$databaseName-*.dump" |
            Where-Object { $_.LastWriteTime -lt $retentionCutoff }) {
            $resolvedFile = [System.IO.Path]::GetFullPath($oldArchive.FullName)
            if ($resolvedFile.StartsWith($resolvedFolder + [System.IO.Path]::DirectorySeparatorChar,
                    [System.StringComparison]::OrdinalIgnoreCase)) {
                Remove-Item -LiteralPath $resolvedFile -Force
                $checksumFile = "$resolvedFile.sha256"
                if (Test-Path -LiteralPath $checksumFile) {
                    Remove-Item -LiteralPath $checksumFile -Force
                }
            }
        }
    }

    $logLine = "$(Get-Date -Format o) SUCCESS $archiveName Entries=$($archiveEntries.Count) OneDriveVerified=$oneDriveCopyVerified"
    Add-Content -LiteralPath $logFile -Value $logLine -Encoding utf8
    Write-Output $logLine
    if (-not $oneDriveCopyVerified) {
        Write-Warning 'Local backup is valid, but OneDrive is unavailable; no off-device copy was verified.'
    }
} catch {
    if (Test-Path -LiteralPath $partialPath) {
        Remove-Item -LiteralPath $partialPath -Force
    }
    $logLine = "$(Get-Date -Format o) FAILED $($_.Exception.Message)"
    Add-Content -LiteralPath $logFile -Value $logLine -Encoding utf8
    throw
}
