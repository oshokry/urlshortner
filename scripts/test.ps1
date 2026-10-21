$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force build/test-classes | Out-Null
$sources = Get-ChildItem src/main/java,src/test/java -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& java -m jdk.compiler/com.sun.tools.javac.Main --release 17 -Xlint:all -Werror -d build/test-classes $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& java -ea -cp build/test-classes com.oshokry.shortener.ShortenerTests
exit $LASTEXITCODE
