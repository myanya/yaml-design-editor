@echo off
rem Headless CLI launcher for Windows (cmd / PowerShell). Usage: yde <command> [args...]   e.g.  yde validate samples\todos
rem Runs the same commands as "gradlew runCli", but keeps Gradle out of the output path:
rem Gradle re-encodes the CLI output to the console code page, which garbles Japanese text.
rem Here Gradle only refreshes the CLI files (cli\build\yde-cli); the CLI itself runs on the toolchain Java and writes UTF-8.
rem The console code page is switched to UTF-8 (65001) while the CLI runs, then restored.
rem This file is ASCII only on purpose (cmd reads it in the console code page). Japanese notes: docs/manual/cli.md
setlocal
set "YDE_ROOT=%~dp0"
set "YDE_DIST=%YDE_ROOT%cli\build\yde-cli"
call "%YDE_ROOT%gradlew.bat" -p "%YDE_ROOT%." :cli:cliDist -q --console=plain 1>&2
if errorlevel 1 exit /b 1
set /p YDE_JAVA_HOME=<"%YDE_DIST%\java-home.txt"
set "YDE_OLD_CP="
for /f "tokens=2 delims=:" %%a in ('chcp') do set "YDE_OLD_CP=%%a"
chcp 65001 >nul
"%YDE_JAVA_HOME%\bin\java.exe" -Dfile.encoding=UTF-8 -Dstdout.encoding=UTF-8 -Dstderr.encoding=UTF-8 -cp "%YDE_DIST%\lib\*" dev.toros.yde.cli.MainKt %*
set "YDE_EXIT=%ERRORLEVEL%"
if defined YDE_OLD_CP chcp %YDE_OLD_CP% >nul
exit /b %YDE_EXIT%
