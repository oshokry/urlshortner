$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force build/classes | Out-Null
$sources = Get-ChildItem src/main/java -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -Xlint:all -Werror -d build/classes $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -cp build/classes com.oshokry.shortener.Main
