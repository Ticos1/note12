@echo off
setlocal EnableDelayedExpansion
rem ============================================================================
rem  MultiIdle no PC - abre as contas do pokeidle.io no Brave, cada uma numa
rem  janela propria e ISOLADA (login, cookies e dados separados), lado a lado.
rem  Nao instala nada e nao mexe no seu Brave normal.
rem
rem  Lembrete: a mesma conta nao roda em dois lugares - feche no celular antes.
rem ============================================================================

rem Quantas contas abrir (1 a 4):
set CONTAS=3
set "URL=https://pokeidle.io/app"
rem normal = janela comum do Brave, com barra e EXTENSOES (instale em cada janela uma vez)
rem app    = janela limpa, sem barra (as extensoes rodam, mas os icones nao aparecem)
set MODO=normal
rem Onde ficam os logins (e extensoes) de cada conta:
set "PASTA=%LOCALAPPDATA%\MultiIdle"

rem --- acha o Brave ---
set "BRAVE=%ProgramFiles%\BraveSoftware\Brave-Browser\Application\brave.exe"
if not exist "!BRAVE!" set "BRAVE=%LOCALAPPDATA%\BraveSoftware\Brave-Browser\Application\brave.exe"
if not exist "!BRAVE!" set "BRAVE=%ProgramFiles(x86)%\BraveSoftware\Brave-Browser\Application\brave.exe"
if not exist "!BRAVE!" (
  echo Nao achei o Brave neste PC.
  pause
  exit /b 1
)

rem --- tamanho da tela (descontando a barra de tarefas) ---
set W=
set H=
for /f "tokens=1,2" %%a in ('powershell -NoProfile -Command "Add-Type -AssemblyName System.Windows.Forms; $a=[System.Windows.Forms.Screen]::PrimaryScreen.WorkingArea; Write-Output ($a.Width.ToString() + ' ' + $a.Height.ToString())" 2^>nul') do (
  set W=%%a
  set H=%%b
)
if not defined W set W=1920
if not defined H set H=1040

rem --- uma janela por conta, em colunas ---
set /a LARG=W/CONTAS
if /i "%MODO%"=="app" (set "ABRIR=--app=!URL!") else (set "ABRIR=--new-window !URL!")
for /L %%i in (1,1,%CONTAS%) do (
  set /a "X=(%%i-1)*LARG"
  start "" "!BRAVE!" ^
    --user-data-dir="!PASTA!\conta%%i" ^
    --no-first-run --no-default-browser-check ^
    --disable-background-timer-throttling ^
    --disable-renderer-backgrounding ^
    --disable-backgrounding-occluded-windows ^
    --window-position=!X!,0 --window-size=!LARG!,!H! ^
    !ABRIR!
)
exit /b 0
