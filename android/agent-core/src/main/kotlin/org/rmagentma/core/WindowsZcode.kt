/*
 * rmAgentMa
 * Copyright 2026 rmAgentMa contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.rmagentma.core

/** The desktop build's executable; its directory is the installation the CLI entry is derived from. */
private const val ZCODE_DESKTOP_EXECUTABLE = "ZCode.exe"

/** The CLI entry inside a ZCode desktop installation, relative to the installation directory. */
private const val ZCODE_CLI_ENTRY = "resources\\glm\\zcode.cjs"

/** The runtime directory this app prepares under the user's local application data directory. */
private const val ZCODE_RUNTIME_DIRECTORY = "rmagentma\\zcode"

/**
 * One line a Windows shell parses; cmd.exe's own limit. The relay's base64 is what has to fit, because that is
 * the only part of the line the relay adds.
 */
private const val WINDOWS_COMMAND_LINE_LIMIT = 8191

/**
 * The PowerShell lookup for a ZCode the Windows `where` command did not find.
 *
 * The desktop build does not put a launcher on `PATH`, so its own registration is read instead: first the `zcode`
 * protocol handler, then the per-user uninstall entry. Both are read-only; nothing is written to the host. Only a
 * path that is the absolute `ZCode.exe` inside an existing installation is reported, and a lookup that could not
 * be read at all answers `probe_unavailable` for zcode alone rather than guessing an installation.
 *
 * The text travels inside one cmd.exe command line through the target's `-EncodedCommand` relay, so it is written
 * densely: the relay's base64 grows with every character here, and the whole line has a fixed length limit.
 */
internal fun windowsZcodeLookupPrelude(): String = buildString {
    appendLine("function RmzPath([object]\$v){")
    appendLine("\$t=[string]\$v;if(-not \$t -or \$t.Length -gt 4096 -or \$t -match '[\\x00-\\x1f\\x7f]'){return \$null};")
    appendLine("if(\$t -match '^[A-Za-z]:[\\\\/]' -or \$t -match '^\\\\\\\\[^\\\\]+\\\\'){return \$t};return \$null}")
    appendLine("function RmzExecutable([object]\$v){")
    appendLine("\$t=([string]\$v).Trim();if(-not \$t){return \$null};")
    appendLine("if(\$t[0] -eq '\"'){\$i=\$t.IndexOf('\"',1);if(\$i -lt 0){return \$null};return \$t.Substring(1,\$i-1)};")
    appendLine("\$m=[regex]::Match(\$t,'^(.+?\\.exe)(?=\\s|\$)',1);if(\$m.Success){return \$m.Groups[1].Value};")
    appendLine("return @(\$t -split '\\s+',2)[0]}")
    appendLine("function RmzZcode([object]\$v){")
    appendLine("\$p=RmzPath \$v;if(-not \$p -or [IO.Path]::GetFileName(\$p) -ine '$ZCODE_DESKTOP_EXECUTABLE'){return \$null};")
    appendLine("if(-not(Test-Path -LiteralPath \$p -PathType Leaf)){return \$null};return \$p}")
    appendLine("function FindRmzZcode{")
    appendLine("\$ok=\$true;\$f=\$null;")
    appendLine("try{")
    appendLine("\$h='HKCU:\\Software\\Classes\\zcode\\shell\\open\\command'")
    appendLine("if(Test-Path -LiteralPath \$h){\$f=RmzZcode (RmzExecutable (Get-ItemProperty -LiteralPath \$h -ErrorAction Stop).'(default)')}")
    appendLine("}catch{\$ok=\$false}")
    appendLine("if(-not \$f){")
    appendLine("try{")
    appendLine("\$u='HKCU:\\Software\\Microsoft\\Windows\\CurrentVersion\\Uninstall'")
    appendLine("if(Test-Path -LiteralPath \$u){")
    appendLine("foreach(\$k in @(Get-ChildItem -LiteralPath \$u -ErrorAction Stop)){")
    appendLine("if(\$f){break}")
    appendLine("try{\$q=Get-ItemProperty -LiteralPath \$k.PSPath -ErrorAction Stop}catch{\$ok=\$false;continue}")
    appendLine("if([string]\$q.DisplayName -notlike 'ZCode*'){continue}")
    appendLine("foreach(\$s in @(\$q.UninstallString,\$q.DisplayIcon)){")
    appendLine("\$e=RmzExecutable ([string]\$s -replace ',\\s*[0-9]+\\s*\$','');if(-not \$e){continue}")
    appendLine("\$g=[IO.Path]::GetDirectoryName(\$e);if(-not \$g){continue}")
    appendLine("\$f=RmzZcode (Join-Path \$g '$ZCODE_DESKTOP_EXECUTABLE');if(\$f){break}")
    appendLine("}")
    appendLine("}")
    appendLine("}")
    appendLine("}catch{\$ok=\$false}")
    appendLine("}")
    appendLine("if(\$f){return \$f};if(\$ok){return \$null};return ''}")
}

/** The zcode answer the lookup above prints: one existing installer, or the reason no installer is named. */
internal fun windowsZcodeLookupAnswer(): String = "\$z=FindRmzZcode; " +
    "if(\$z -eq ''){ '$WINDOWS_LOOKUP_UNAVAILABLE ${AgentKind.ZCODE.command}' } " +
    "elseif(\$z){ \"found ${AgentKind.ZCODE.command} \$z\" } " +
    "else{ 'missing ${AgentKind.ZCODE.command}' }"

/**
 * The PowerShell text that starts the ZCode protocol server of the desktop build at [executable] for [cwd].
 *
 * The desktop build refuses to start unless `provider\zcode-builtin.json` sits beside its entry script, and the
 * runtime copy of that file changes with the installed version. This text therefore keeps its own entry script
 * under `%LOCALAPPDATA%\rmagentma\zcode` and a directory junction named `provider` beside it that points at the
 * newest installed runtime, reusing a junction already pointing there and rebuilding one left on an older
 * version. Nothing is copied and nothing below the ZCode installation is written.
 *
 * The executable then runs with `ELECTRON_RUN_AS_NODE=1`, its own Electron binary acting as node. The process is
 * started with inherited standard handles and no redirection, because the protocol is read from stdin and written
 * to stdout. `ProcessStartInfo` is used rather than PowerShell's `&` operator, which drops input written to a
 * piped stdin after the command started.
 *
 * The text runs through [windowsShellCommand]'s `-EncodedCommand` relay; its base64 form is bounded by the
 * Windows command line the relay travels on. Every variable value is embedded as a PowerShell single-quoted
 * literal, and every argument is quoted by the Windows command line rules, so no value can break out of the text.
 *
 * @throws IllegalArgumentException when [executable] is not an absolute Windows path naming `ZCode.exe`, or when
 * [cwd] is not an absolute Windows path. A path carrying a control character is not one Windows can address and
 * is refused before it reaches the script.
 */
fun windowsZcodeCommand(executable: String, cwd: String): String {
    require(isWindowsAbsolutePath(executable)) { "ZCode executable must be a Windows absolute path" }
    val normalizedExecutable = executable.replace('/', '\\')
    require(windowsFileName(normalizedExecutable).equals(ZCODE_DESKTOP_EXECUTABLE, ignoreCase = true)) {
        "ZCode executable must be $ZCODE_DESKTOP_EXECUTABLE"
    }
    require(isWindowsAbsolutePath(cwd)) { "Working directory must be a Windows absolute path" }
    val install = windowsParentDirectory(normalizedExecutable)
    require(isWindowsAbsolutePath(install)) { "ZCode executable must name an installation directory" }
    val cliEntry = install.trimEnd('\\') + "\\" + ZCODE_CLI_ENTRY
    val entryBody = "require(" + javascriptLiteral(cliEntry) + ")"
    val arguments = " app-server --cwd " + windowsArgument(cwd) + " --mode build"
    val script = buildString {
        appendLine("\$ErrorActionPreference = 'Stop'")
        appendLine("\$ProgressPreference = 'SilentlyContinue'")
        appendLine("\$cli = ${powershellLiteral(cliEntry)}")
        appendLine("if (-not (Test-Path -LiteralPath \$cli -PathType Leaf)) { [Console]::Error.WriteLine('rmagentma_zcode_cli_missing'); exit 1 }")
        appendLine("\$base = Join-Path \$env:LOCALAPPDATA '$ZCODE_RUNTIME_DIRECTORY'")
        appendLine("[IO.Directory]::CreateDirectory(\$base) | Out-Null")
        appendLine("\$entry = Join-Path \$base 'entry.cjs'")
        appendLine("\$body = ${powershellLiteral(entryBody)}")
        appendLine("\$current = \$null")
        appendLine("if (Test-Path -LiteralPath \$entry -PathType Leaf) { \$current = Get-Content -LiteralPath \$entry -Raw }")
        appendLine("if (\$current -cne \$body) { [IO.File]::WriteAllText(\$entry, \$body, [Text.UTF8Encoding]::new(\$false)) }")
        appendLine("\$root = Join-Path \$env:USERPROFILE '.zcode\\v2\\runtime\\provider\\windows-x86_64'")
        appendLine("\$target = \$null")
        appendLine("if (Test-Path -LiteralPath \$root -PathType Container) {")
        appendLine("  \$versions = @(Get-ChildItem -LiteralPath \$root -Directory -Force |")
        appendLine("    Where-Object { \$_.Name -match '^\\d{1,9}(\\.\\d{1,9}){1,3}\$' } | Sort-Object { [version]\$_.Name } -Descending)")
        appendLine("  foreach (\$version in \$versions) {")
        appendLine("    \$endpoints = @(Get-ChildItem -LiteralPath \$version.FullName -Directory -Force -Filter 'endpoint-*')")
        appendLine("    foreach (\$endpoint in \$endpoints) {")
        appendLine("      if (Test-Path -LiteralPath (Join-Path \$endpoint.FullName 'zcode-builtin.json') -PathType Leaf) {")
        appendLine("        \$target = \$endpoint.FullName")
        appendLine("        break")
        appendLine("      }")
        appendLine("    }")
        appendLine("    if (\$target) { break }")
        appendLine("  }")
        appendLine("}")
        appendLine("if (-not \$target) { [Console]::Error.WriteLine('rmagentma_zcode_provider_missing'); exit 1 }")
        appendLine("\$provider = Join-Path \$base 'provider'")
        appendLine("\$linked = \$null")
        appendLine("if (Test-Path -LiteralPath \$provider) {")
        appendLine("  \$item = Get-Item -LiteralPath \$provider -Force")
        appendLine("  if (-not (\$item.Attributes -band [IO.FileAttributes]::ReparsePoint)) {")
        appendLine("    [Console]::Error.WriteLine('rmagentma_zcode_provider_conflict')")
        appendLine("    exit 1")
        appendLine("  }")
        appendLine("  \$linked = @(\$item.Target)[0]")
        appendLine("}")
        appendLine("if (\$linked) { \$linked = \$linked.TrimEnd('\\') }")
        appendLine("if (\$linked -ne \$target) {")
        appendLine("  if (\$linked) { [IO.Directory]::Delete(\$provider, \$false) }")
        appendLine("  New-Item -ItemType Junction -Path \$provider -Target \$target | Out-Null")
        appendLine("  if (-not (Test-Path -LiteralPath (Join-Path \$provider 'zcode-builtin.json') -PathType Leaf)) {")
        appendLine("    [Console]::Error.WriteLine('rmagentma_zcode_provider_unlinked')")
        appendLine("    exit 1")
        appendLine("  }")
        appendLine("}")
        appendLine("\$env:ELECTRON_RUN_AS_NODE = '1'")
        appendLine("\$start = New-Object System.Diagnostics.ProcessStartInfo")
        appendLine("\$start.FileName = ${powershellLiteral(executable)}")
        appendLine("\$start.Arguments = '\"' + \$entry + '\"' + ${powershellLiteral(arguments)}")
        appendLine("\$start.WorkingDirectory = ${powershellLiteral(cwd)}")
        appendLine("\$start.UseShellExecute = \$false")
        appendLine("\$process = [System.Diagnostics.Process]::Start(\$start)")
        appendLine("\$process.WaitForExit()")
        appendLine("exit \$process.ExitCode")
    }
    require(windowsShellCommand(script).length <= WINDOWS_COMMAND_LINE_LIMIT) {
        "The launch text does not fit one Windows command line"
    }
    return script
}

/** The file name of an absolute Windows path, which is the last component after either separator. */
private fun windowsFileName(path: String): String = path.substringAfterLast('\\').substringAfterLast('/')

/** The parent directory of an absolute Windows path, keeping a drive root (`C:\`) as the parent of `C:\x.exe`. */
private fun windowsParentDirectory(path: String): String {
    val cut = path.lastIndexOf('\\')
    if (cut < 0) return ""
    if (cut == 2 && path[1] == ':') return path.substring(0, 3)
    return path.substring(0, cut)
}

/** [value] as a JavaScript single-quoted string: a backslash and a quote are escaped, nothing else is special. */
private fun javascriptLiteral(value: String): String = "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'"

/**
 * [value] as one argument of a Windows command line. An argument with no space or quote travels as it is; any
 * other argument is quoted, and backslashes are doubled only where they precede a quote or the closing quote.
 */
internal fun windowsArgument(value: String): String {
    if (value.isNotEmpty() && value.none { it == ' ' || it == '\t' || it == '\n' || it == '\u000B' || it == '"' }) return value
    val quoted = StringBuilder("\"")
    var backslashes = 0
    for (character in value) {
        when (character) {
            '\\' -> backslashes++

            '"' -> {
                repeat(backslashes * 2 + 1) { quoted.append('\\') }
                quoted.append('"')
                backslashes = 0
            }

            else -> {
                repeat(backslashes) { quoted.append('\\') }
                backslashes = 0
                quoted.append(character)
            }
        }
    }
    repeat(backslashes * 2) { quoted.append('\\') }
    return quoted.append('"').toString()
}
