@echo off

set CGO_LDFLAGS=-Wl,-z,max-page-size=16384

gomobile bind -v -androidapi 21 -trimpath -ldflags="-s -buildid=" -tags="with_clash" "github.com/exclavenetwork/libexclavecore" "github.com/exclavenetwork/exclave-core/v5/common/speedtest"
if errorlevel 1 (
    exit /b 1
)

set "proj=..\..\app\libs"

if exist "%proj%" (
    copy /Y libexclavecore.aar "%proj%"
)
