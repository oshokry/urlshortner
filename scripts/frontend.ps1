param([ValidateSet('dev', 'build', 'preview', 'test')][string]$Command = 'dev')
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
if (-not (Get-Command npm.cmd -ErrorAction SilentlyContinue)) {
    throw 'Install Node.js 24 LTS, version 24.15 or newer, then open a new terminal.'
}
if (-not (Test-Path frontend/node_modules)) {
    & npm.cmd --prefix frontend ci
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}
& npm.cmd --prefix frontend run $Command
exit $LASTEXITCODE
