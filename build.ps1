# Build build\aa-tweak.dex from src\AaTweak.java on Windows.
# Needs a JDK (11 or newer) and the Android SDK with build-tools and at least one platform installed.
$ErrorActionPreference = 'Stop'
Set-Location $PSScriptRoot

$sdk = $env:ANDROID_HOME
if (-not $sdk) { $sdk = $env:ANDROID_SDK_ROOT }
if (-not $sdk) { $sdk = Join-Path $env:LOCALAPPDATA 'Android\Sdk' }
if (-not (Test-Path $sdk)) { throw "Android SDK not found. Set ANDROID_HOME to your Android SDK folder." }

# Newest platform android.jar (folders look like android-36 or android-37.0).
$platform = Get-ChildItem (Join-Path $sdk 'platforms') -Directory -ErrorAction SilentlyContinue |
    Where-Object { Test-Path (Join-Path $_.FullName 'android.jar') } |
    Sort-Object { [int]($_.Name -replace '^android-(\d+).*$', '$1') } |
    Select-Object -Last 1
if (-not $platform) { throw "No Android platform in $sdk\platforms. Install one, e.g.: sdkmanager `"platforms;android-36`"" }
$jar = Join-Path $platform.FullName 'android.jar'

# Newest build-tools (folders look like 36.0.0).
$tools = Get-ChildItem (Join-Path $sdk 'build-tools') -Directory -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match '^\d+(\.\d+)*$' -and (Test-Path (Join-Path $_.FullName 'd8.bat')) } |
    Sort-Object { [version]$_.Name } |
    Select-Object -Last 1
if (-not $tools) { throw "No build-tools in $sdk\build-tools. Install them, e.g.: sdkmanager `"build-tools;36.0.0`"" }
$d8 = Join-Path $tools.FullName 'd8.bat'

$javac = 'javac'
if ($env:JAVA_HOME -and (Test-Path (Join-Path $env:JAVA_HOME 'bin\javac.exe'))) { $javac = Join-Path $env:JAVA_HOME 'bin\javac.exe' }

if (Test-Path build) { Remove-Item -Recurse -Force build }
New-Item -ItemType Directory -Force build\classes | Out-Null
& $javac --release 11 -Xlint:-options -cp $jar -d build\classes src\AaTweak.java
if ($LASTEXITCODE -ne 0) { throw "javac failed" }
$classes = Get-ChildItem build\classes -Filter *.class | ForEach-Object { $_.FullName }
& $d8 --release --min-api 29 --lib $jar --output build $classes
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }
Move-Item build\classes.dex build\aa-tweak.dex
Write-Output "Built build\aa-tweak.dex"
