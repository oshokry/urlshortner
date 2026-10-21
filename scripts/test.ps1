$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
. (Join-Path $PSScriptRoot 'java-env.ps1')
New-Item -ItemType Directory -Force build/test-classes | Out-Null
$sources = Get-ChildItem src/main/java,src/test/java -Recurse -Filter *.java | ForEach-Object { $_.FullName }
& $ProjectJava -m jdk.compiler/com.sun.tools.javac.Main --release 17 -encoding UTF-8 -Xlint:all -Werror -d build/test-classes $sources
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
& $ProjectJava -ea -cp build/test-classes com.oshokry.shortener.ShortenerTests
exit $LASTEXITCODE
