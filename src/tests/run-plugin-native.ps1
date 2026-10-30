param([string]$Serial='emulator-5582')
$ErrorActionPreference='Stop'
# 只允许独立模拟器；不把测试包安装到用户手机。
if(-not $Serial.StartsWith('emulator-')){throw '仅允许在专用模拟器运行'}
$taskRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Push-Location $taskRoot
try {
    $sdkLine=Get-Content local.properties | Where-Object {$_ -match '^sdk.dir='} | Select-Object -First 1
    $sdkPath=($sdkLine -replace '^sdk.dir=','').Replace('\:',':').Replace('\\','\')
    $taskAdb=Join-Path $sdkPath 'platform-tools/adb.exe'
    if((& $taskAdb -s $Serial shell getprop sys.boot_completed) -notmatch '^1'){throw '专用模拟器尚未启动完成'}
    foreach($apk in @('demo/build/outputs/apk/debug/demo-debug.apk','demo/build/outputs/apk/androidTest/debug/demo-debug-androidTest.apk')){
        & $taskAdb -s $Serial install -r $apk
        if($LASTEXITCODE -ne 0){throw '测试包安装失败'}
    }
    $result=& $taskAdb -s $Serial shell am instrument -w com.yuan.follow.demo.test/com.local.yuanatlas.PluginChecks
    New-Item -ItemType Directory -Force output | Out-Null
    $result | Set-Content -Encoding utf8 output/star-native-tests.txt
    $result
    # am instrument 有时在测试失败时仍返回 0，必须核对实际报告。
    $report=$result -join "
"
    if($report -match 'AssertionError|Exception|FAIL|Process crashed' -or $report -notmatch 'PASS native blue/purple/orange' -or $report -notmatch 'all 9 target/observed combinations'){throw '原生回归未通过，请检查 output/star-native-tests.txt'}
} finally {Pop-Location}
