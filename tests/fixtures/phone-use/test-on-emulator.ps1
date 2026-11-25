param(
    [Parameter(Mandatory)][ValidatePattern('^emulator-[0-9]+$')][string]$Serial,
    [ValidateSet('builtin','autojs')][string]$Backend = 'builtin',
    [string]$Sdk = $env:ANDROID_HOME
)
$ErrorActionPreference = 'Stop'
$adb = Join-Path $Sdk 'platform-tools/adb.exe'
$apk = Join-Path $PSScriptRoot 'build/phone-use-fixture.apk'
if (!(Test-Path $apk)) { throw 'Build the fixture first (build.ps1).' }
$state = & $adb -s $Serial get-state
if ($LASTEXITCODE -ne 0 -or $state -ne 'device') { throw "Test emulator is not available: $Serial" }
& $adb -s $Serial install -r $apk
if ($LASTEXITCODE -ne 0) { throw 'Fixture installation failed.' }
$test = 'com.androidharness.app.androiduse.PhoneUseFixtureDeviceTest'
$runner = 'com.androidharness.app.debug.test/androidx.test.runner.AndroidJUnitRunner'
$log = & $adb -s $Serial shell am instrument -w -r -e class $test -e phoneUseFixture true -e phoneUseFixtureBackend $Backend $runner
$log
if ($LASTEXITCODE -ne 0 -or (($log -join "`n") -notmatch 'OK \(2 tests\)')) { throw 'The two phone-use checks did not pass; see the instrumentation output above.' }
