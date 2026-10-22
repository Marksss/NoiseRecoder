param(
    [Parameter(Mandatory=$true)][string]$JavaHome,
    [Parameter(Mandatory=$true)][string]$KotlinHome,
    [Parameter(Mandatory=$true)][string]$AndroidJar,
    [Parameter(Mandatory=$true)][string]$JUnitJar,
    [Parameter(Mandatory=$true)][string]$HamcrestJar
)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$outputDir = Join-Path $projectRoot '.verification'
New-Item -ItemType Directory -Force $outputDir | Out-Null
$java = Join-Path $JavaHome 'bin/java.exe'
$kotlinLib = Join-Path $KotlinHome 'lib'
$mainFiles = Get-ChildItem (Join-Path $projectRoot 'app/src/main/java/com/example/noiserecorder/*.kt') | ForEach-Object FullName
$testFiles = Get-ChildItem (Join-Path $projectRoot 'app/src/test/java/com/example/noiserecorder/*.kt') | ForEach-Object FullName
$testJar = Join-Path $outputDir 'tests.jar'
& $java "-Dkotlin.home=$KotlinHome" -cp "$kotlinLib/*" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler @mainFiles @testFiles -classpath "$AndroidJar;$JUnitJar;$HamcrestJar" -jvm-target 17 -d $testJar
if ($LASTEXITCODE -ne 0) { throw 'Kotlin compilation failed' }
& $java -cp "$testJar;$kotlinLib/kotlin-stdlib.jar;$JUnitJar;$HamcrestJar" org.junit.runner.JUnitCore com.example.noiserecorder.AudioLogicTest com.example.noiserecorder.WavWriterTest com.example.noiserecorder.ReportLogicTest
if ($LASTEXITCODE -ne 0) { throw 'JUnit verification failed' }
Write-Output 'Source compilation and unit tests passed. APK assembly and device tests are separate checks.'
