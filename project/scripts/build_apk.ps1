param(
    [switch]$Release = $true,
    [switch]$BuildAab = $true,
    [switch]$SignReleaseApk = $true
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path ./app/build.gradle.kts)) {
    throw "Run this script from project root."
}

$projectRoot = (Resolve-Path .).Path
$localSdk = Join-Path $projectRoot ".android-sdk"
$localGradle = Join-Path $projectRoot ".tools\gradle-8.9\bin\gradle.bat"
$gradlew = Join-Path $projectRoot "gradlew.bat"

if (Test-Path $localSdk) {
    $env:ANDROID_SDK_ROOT = $localSdk
    $env:ANDROID_HOME = $localSdk
}

if (-not $env:JAVA_HOME) {
    if (Test-Path "C:\Program Files\Java\jdk-17") {
        $env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
    }
}

function Get-GradleCommand {
    if (Test-Path $localGradle) {
        return $localGradle
    }
    if (Test-Path $gradlew) {
        return $gradlew
    }
    $globalGradle = Get-Command gradle -ErrorAction SilentlyContinue
    if ($globalGradle) {
        return $globalGradle.Source
    }
    throw "No Gradle executable found. Add .tools/gradle-8.9 or install Gradle globally."
}

$gradleCmd = Get-GradleCommand

function Invoke-Gradle {
    param([string[]]$GradleArgs)

    & $gradleCmd @GradleArgs
    if ($LASTEXITCODE -ne 0) {
        throw "Gradle command failed: $gradleCmd $($GradleArgs -join ' ')"
    }
}

function Sign-ReleaseApks {
    if (-not $Release) {
        return
    }
    if (-not $SignReleaseApk) {
        return
    }
    if (-not $env:ANDROID_SDK_ROOT) {
        Write-Warning "ANDROID_SDK_ROOT is not set. Skip APK signing."
        return
    }

    $apksigner = Join-Path $env:ANDROID_SDK_ROOT "build-tools\35.0.0\apksigner.bat"
    if (-not (Test-Path $apksigner)) {
        Write-Warning "apksigner not found ($apksigner). Skip APK signing."
        return
    }

    $keystore = Join-Path $projectRoot ".tools\debug.keystore"
    if (-not (Test-Path $keystore)) {
        New-Item -ItemType Directory -Force -Path (Split-Path $keystore -Parent) | Out-Null
        $keytool = if ($env:JAVA_HOME) {
            Join-Path $env:JAVA_HOME "bin\keytool.exe"
        } else {
            "keytool"
        }
        & $keytool -genkeypair -v `
            -keystore $keystore `
            -storepass android `
            -keypass android `
            -alias androiddebugkey `
            -dname "CN=Android Debug,O=Android,C=US" `
            -keyalg RSA `
            -keysize 2048 `
            -validity 10000
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to create debug keystore."
        }
    }

    $unsignedApks = Get-ChildItem "app/build/outputs/apk/release" -Filter "*-unsigned.apk" -ErrorAction SilentlyContinue
    foreach ($apk in $unsignedApks) {
        $signedPath = $apk.FullName -replace "-unsigned\.apk$", ".apk"
        & $apksigner sign `
            --ks $keystore `
            --ks-pass pass:android `
            --key-pass pass:android `
            --ks-key-alias androiddebugkey `
            --out $signedPath `
            $apk.FullName
        if ($LASTEXITCODE -ne 0) {
            throw "Failed to sign APK: $($apk.FullName)"
        }
    }
}

$task = if ($Release) { ":app:assembleRelease" } else { ":app:assembleDebug" }

Write-Host "[build] Using Gradle: $gradleCmd"
if ($env:ANDROID_SDK_ROOT) {
    Write-Host "[build] ANDROID_SDK_ROOT=$($env:ANDROID_SDK_ROOT)"
}
if ($env:JAVA_HOME) {
    Write-Host "[build] JAVA_HOME=$($env:JAVA_HOME)"
}

Write-Host "[build] Running $task"
Invoke-Gradle @("clean", $task)

if ($BuildAab -and $Release) {
    Write-Host "[build] Running :app:bundleRelease"
    Invoke-Gradle @(":app:bundleRelease")
}

Sign-ReleaseApks

Write-Host "[done] APK outputs:"
Get-ChildItem -Path ./app/build/outputs/apk -Recurse -Filter *.apk -ErrorAction SilentlyContinue |
    Select-Object -ExpandProperty FullName

if ($BuildAab -and $Release) {
    Write-Host "[done] AAB outputs:"
    Get-ChildItem -Path ./app/build/outputs/bundle -Recurse -Filter *.aab -ErrorAction SilentlyContinue |
        Select-Object -ExpandProperty FullName
}
