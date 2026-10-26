param(
    [string]$JavaHome = $env:JAVA_HOME,
    [string]$AndroidSdk = $env:ANDROID_HOME,
    [string]$GradleUserHome = "",
    [string[]]$Tasks = @("assembleDebug","testDebugUnitTest","lintDebug")
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path -LiteralPath (Join-Path $JavaHome "bin\java.exe"))) { throw "Please provide a JDK 17 directory with -JavaHome." }
if (-not (Test-Path -LiteralPath (Join-Path $AndroidSdk "platforms\android-35\android.jar"))) { throw "Please install Android SDK platform 35 and provide -AndroidSdk." }
$env:JAVA_HOME = $JavaHome
$env:ANDROID_HOME = $AndroidSdk
$projectDirectory = Split-Path -Parent $PSScriptRoot
Push-Location $projectDirectory
try {
    $arguments = @("--no-daemon","--console=plain")
    if ($GradleUserHome) { $arguments += @("-g",$GradleUserHome) }
    & ".\gradlew.bat" @arguments @Tasks
    if ($LASTEXITCODE -ne 0) { throw "Gradle failed: $LASTEXITCODE" }
} finally { Pop-Location }
