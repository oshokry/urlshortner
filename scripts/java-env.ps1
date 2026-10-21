# Find a usable JDK without relying on an older Java earlier in PATH.
function Resolve-ProjectJava {
    $candidates = @()
    if ($env:JAVA_HOME) {
        $candidates += Join-Path $env:JAVA_HOME 'bin\java.exe'
    }
    $onPath = Get-Command java -CommandType Application -ErrorAction SilentlyContinue
    if ($onPath) { $candidates += $onPath.Source }

    # Common Windows JDK installer locations; no global environment changes.
    if ($env:ProgramFiles) {
        foreach ($vendor in @('Microsoft', 'Eclipse Adoptium', 'Java')) {
            $vendorPath = Join-Path $env:ProgramFiles $vendor
            if (Test-Path $vendorPath) {
                $candidates += Get-ChildItem $vendorPath -Directory -Filter '*jdk*' -ErrorAction SilentlyContinue |
                    Sort-Object Name -Descending |
                    ForEach-Object { Join-Path $_.FullName 'bin\java.exe' }
            }
        }
    }

    foreach ($candidate in ($candidates | Select-Object -Unique)) {
        if (-not (Test-Path $candidate -PathType Leaf)) { continue }
        # Windows PowerShell 5 treats native stderr as error records. Java prints
        # its version there, so capture it without Stop interrupting detection.
        $previousPreference = $ErrorActionPreference
        try {
            $ErrorActionPreference = 'Continue'
            $versionText = (& $candidate -version 2>&1 | Out-String)
            $versionExitCode = $LASTEXITCODE
        } finally {
            $ErrorActionPreference = $previousPreference
        }
        if ($versionExitCode -ne 0 -or $versionText -notmatch 'version\s+"(?<major>\d+)(?:\.(?<minor>\d+))?') { continue }
        $major = [int]$Matches['major']
        if ($major -eq 1) { $major = [int]$Matches['minor'] }
        if ($major -lt 17) { continue }
        $compiler = Join-Path (Split-Path $candidate -Parent) 'javac.exe'
        if (-not (Test-Path $compiler -PathType Leaf)) { continue }
        Write-Host "Using JDK $major at $candidate"
        return $candidate
    }
    throw @'
JDK 17 or newer is required. No compatible JDK was found.
Install it: winget install --exact --id Microsoft.OpenJDK.17
Then rerun this script. For a custom installation, set JAVA_HOME to its JDK folder.
'@
}

$ProjectJava = Resolve-ProjectJava
