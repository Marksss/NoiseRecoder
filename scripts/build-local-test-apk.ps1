param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$KotlinHome,
    [Parameter(Mandatory=$true)][string]$SdkRoot,
    [string]$Platform = 'android-34',
    [string]$BuildTools = '33.0.0',
    [switch]$InstrumentationOnly
)
# Fallback for an offline machine. Gradle remains the official build path.
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$out = Join-Path $root '.verification'
New-Item -ItemType Directory -Force $out | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
$tools = Join-Path $SdkRoot "build-tools/$BuildTools"
$android = Join-Path $SdkRoot "platforms/$Platform/android.jar"
$klib = Join-Path $KotlinHome 'lib'
function Check-Native { if ($LASTEXITCODE -ne 0) { throw "Native tool exited with $LASTEXITCODE" } }
if (!$InstrumentationOnly) {
    $sources = @(Get-ChildItem "$root/app/src/main/java/com/example/noiserecorder/*.kt" | ForEach-Object FullName)
    & $java "-Dkotlin.home=$KotlinHome" -cp "$klib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @sources -classpath $android -jvm-target 1.8 -d "$out/app.jar"
    Check-Native
} elseif (!(Test-Path "$out/app.jar")) { throw 'Build the app once before using -InstrumentationOnly' }
$tests = @(Get-ChildItem "$root/app/src/androidTest/java/com/example/noiserecorder/*.kt" | ForEach-Object FullName)
& $java "-Dkotlin.home=$KotlinHome" -cp "$klib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @tests -classpath "$android;$out/app.jar" -jvm-target 1.8 -d "$out/instrumentation.jar"
Check-Native
$manifest = [IO.File]::ReadAllText("$root/app/src/main/AndroidManifest.xml").Replace('<manifest xmlns:android=', '<manifest package="com.example.noiserecorder" xmlns:android=').Replace('${applicationId}', 'com.example.noiserecorder').Replace('<application ', '<application android:debuggable="true" ')
[IO.File]::WriteAllText("$out/AndroidManifest.xml", $manifest)
[IO.File]::WriteAllText("$out/TestManifest.xml", '<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.example.noiserecorder.test"><uses-sdk android:minSdkVersion="30" android:targetSdkVersion="35"/><application android:debuggable="true" android:label="Noise smoke tests"/><instrumentation android:name="com.example.noiserecorder.SmokeInstrumentation" android:targetPackage="com.example.noiserecorder" android:label="Noise device verification"/><instrumentation android:name="com.example.noiserecorder.DutyModeInstrumentation" android:targetPackage="com.example.noiserecorder" android:label="Duty mode verification"/><instrumentation android:name="com.example.noiserecorder.RecordDeletionInstrumentation" android:targetPackage="com.example.noiserecorder" android:label="Record deletion verification"/></manifest>')
$key = "$out/test.keystore"
if (!(Test-Path -LiteralPath $key)) {
    & "$JavaHome/bin/keytool.exe" -genkeypair -keystore $key -storepass android -keypass android -alias androiddebugkey -dname 'CN=Android Debug,O=Android,C=US' -keyalg RSA -keysize 2048 -validity 3650
    Check-Native
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$kinds = if ($InstrumentationOnly) { @('instrumentation') } else { @('app', 'instrumentation') }
foreach ($kind in $kinds) {
    $dex = "$out/dex-$kind"
    New-Item -ItemType Directory -Force $dex | Out-Null
    $argsD8 = @('--min-api', '30', '--lib', $android, '--output', $dex, "$out/$kind.jar")
    if ($kind -eq 'app') { $argsD8 += "$klib/kotlin-stdlib.jar" }
    else { $argsD8 += @('--classpath', "$out/app.jar", '--classpath', "$klib/kotlin-stdlib.jar") }
    & $java -cp "$tools/lib/d8.jar" com.android.tools.r8.D8 @argsD8 *> "$out/d8-$kind.log"
    Check-Native
    $manifestPath = if ($kind -eq 'app') { "$out/AndroidManifest.xml" } else { "$out/TestManifest.xml" }
    & "$tools/aapt2.exe" link -o "$out/$kind-base.apk" --manifest $manifestPath -I $android --min-sdk-version 30 --target-sdk-version 35 --version-code 7 --version-name 1.4.1
    Check-Native
    $zip = [IO.Compression.ZipFile]::Open("$out/$kind-base.apk", 'Update')
    try { Get-ChildItem "$dex/*.dex" | ForEach-Object { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $_.FullName, $_.Name) | Out-Null } } finally { $zip.Dispose() }
    & "$tools/zipalign.exe" -f 4 "$out/$kind-base.apk" "$out/$kind-aligned.apk"
    Check-Native
    & $java -jar "$tools/lib/apksigner.jar" sign --ks $key --ks-pass pass:android --key-pass pass:android --out "$out/noise-$kind-local-test.apk" "$out/$kind-aligned.apk"
    Check-Native
    & $java -jar "$tools/lib/apksigner.jar" verify "$out/noise-$kind-local-test.apk"
    Check-Native
}
Write-Output "Local debug APKs: $out/noise-app-local-test.apk and noise-instrumentation-local-test.apk"
Write-Output 'Fallback uses JVM 1.8 bytecode for old local D8. Official Gradle uses JVM 17 and SDK 35.'
