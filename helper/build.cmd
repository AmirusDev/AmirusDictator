@echo off
rem Builds AmirusDictatorHelper.exe with the C# compiler that ships with Windows (.NET Framework 4).
cd /d "%~dp0"
"%WINDIR%\Microsoft.NET\Framework64\v4.0.30319\csc.exe" /nologo /target:winexe /optimize+ /platform:anycpu ^
  /out:..\AmirusDictatorHelper.exe /win32icon:app.ico ^
  /r:System.Windows.Forms.dll /r:System.Drawing.dll Program.cs
