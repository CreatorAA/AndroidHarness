param(
    [string]$Sdk = $env:ANDROID_HOME,
    [string]$Jdk = $env:JAVA_HOME
)
$ErrorActionPreference = 'Stop'
if (!(Test-Path "$Sdk/platforms/android-34/android.jar")) { throw 'Set -Sdk to the Android SDK with platform 34.' }
if (!(Test-Path "$Jdk/bin/javac.exe")) { throw 'Set -Jdk to a JDK installation.' }
$tools = Join-Path $Sdk 'build-tools/36.0.0'
$android = Join-Path $Sdk 'platforms/android-34/android.jar'
$root = $PSScriptRoot
$out = Join-Path $root 'build'
foreach ($dir in @($out, "$out/generated", "$out/classes")) { [void][IO.Directory]::CreateDirectory($dir) }
function Invoke-Checked($program, [string[]]$arguments) {
    & $program @arguments
    if ($LASTEXITCODE -ne 0) { throw "Command failed ($LASTEXITCODE): $program" }
}
$env:JAVA_HOME = $Jdk
$env:Path = "$Jdk/bin;$env:Path"
Invoke-Checked "$tools/aapt2.exe" @('compile','--dir',"$root/res",'-o',"$out/resources.zip")
Invoke-Checked "$tools/aapt2.exe" @('link','-o',"$out/unsigned.apk",'--manifest',"$root/AndroidManifest.xml",'-I',$android,'--java',"$out/generated","$out/resources.zip")
$fixtureSources = @(Get-ChildItem -LiteralPath "$root/src" -Filter '*.java' -File | ForEach-Object FullName)
Invoke-Checked "$Jdk/bin/javac.exe" (@('--release','8','-encoding','UTF-8','-classpath',$android,'-d',"$out/classes") + $fixtureSources + @("$out/generated/com/androidharness/fixture/phoneuse/R.java"))
Invoke-Checked "$Jdk/bin/jar.exe" @('cf',"$out/classes.jar",'-C',"$out/classes",'.')
Invoke-Checked "$tools/d8.bat" @('--min-api','26','--lib',$android,'--output',$out,"$out/classes.jar")
Invoke-Checked "$Jdk/bin/jar.exe" @('uf',"$out/unsigned.apk",'-C',$out,'classes.dex')
Invoke-Checked "$tools/zipalign.exe" @('-f','4',"$out/unsigned.apk","$out/aligned.apk")
$key = "$out/fixture-debug.keystore"
# Local test-only identity, never used for the product.
if (!(Test-Path $key)) {
    Invoke-Checked "$Jdk/bin/keytool.exe" @('-genkeypair','-keystore',$key,'-storepass','android','-keypass','android','-alias','fixture','-keyalg','RSA','-validity','3650','-dname','CN=Phone Use Test Fixture')
}
Invoke-Checked "$tools/apksigner.bat" @('sign','--ks',$key,'--ks-pass','pass:android','--out',"$out/phone-use-fixture.apk","$out/aligned.apk")
Write-Output "$out/phone-use-fixture.apk"
