const std = @import("std");
const fs = std.fs;
const mem = std.mem;
const process = std.process;

extern "user32" fn MessageBoxA(?*anyopaque, ?[*:0]const u8, ?[*:0]const u8, u32) callconv(.c) c_int;
extern "shell32" fn ShellExecuteA(
    ?*anyopaque,
    ?[*:0]const u8,
    ?[*:0]const u8,
    ?[*:0]const u8,
    ?[*:0]const u8,
    c_int,
) callconv(.c) usize;

const MB_YESNOCANCEL: u32 = 3;
const MB_ICONWARNING: u32 = 0x30;
const IDYES: c_int = 6;
const IDNO: c_int = 7;
const IDCANCEL: c_int = 2;

fn pickJavaWithPowerShell(allocator: mem.Allocator, conf_path: []const u8) !bool {
    const tmpdir = try process.getEnvVarOwned(allocator, "TEMP");
    defer allocator.free(tmpdir);

    const ps_path = try std.fmt.allocPrint(allocator, "{s}\\husi_java_pick_{d}.ps1", .{ tmpdir, @as(u32, @truncate(@as(u64, @intCast(std.time.nanoTimestamp())))) });
    defer allocator.free(ps_path);

    const ps_body =
        \\param([Parameter(Mandatory=$true)][string]$ConfPath)
        \\$ErrorActionPreference = 'Stop'
        \\Add-Type -AssemblyName System.Windows.Forms | Out-Null
        \\$d = New-Object System.Windows.Forms.OpenFileDialog
        \\$d.Filter = 'Java (java.exe;javaw.exe)|java.exe;javaw.exe'
        \\$d.Title = 'Select Java 21+ (java.exe or javaw.exe)'
        \\if ($d.ShowDialog() -ne [System.Windows.Forms.DialogResult]::OK) { exit 2 }
        \\$p = $d.FileName
        \\$dir = Split-Path -Parent $p
        \\$probe = Join-Path $dir 'java.exe'
        \\if (-not (Test-Path -LiteralPath $probe)) { $probe = $p }
        \\$out = & $probe '-version' 2>&1
        \\if ($LASTEXITCODE -ne 0) { Write-Error 'java -version failed'; exit 3 }
        \\if ($out -notmatch 'version "([0-9]+)') { Write-Error 'unrecognized java -version'; exit 3 }
        \\$m = [int]$Matches[1]
        \\if ($m -lt 21) { Write-Error ('Java major version ' + $m + ' is below 21'); exit 3 }
        \\New-Item -ItemType Directory -Force -Path (Split-Path -Parent $ConfPath) | Out-Null
        \\Set-Content -LiteralPath $ConfPath -Encoding utf8 -Value $p
        \\
    ;

    {
        const f = try fs.createFileAbsolute(ps_path, .{});
        defer f.close();
        try f.writeAll(ps_body);
    }

    const argv = [_][]const u8{
        "powershell.exe",
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        ps_path,
        conf_path,
    };

    var child = std.process.Child.init(&argv, allocator);
    child.stdin_behavior = .Ignore;
    child.stdout_behavior = .Ignore;
    child.stderr_behavior = .Ignore;
    const term = try child.spawnAndWait();
    fs.deleteFileAbsolute(ps_path) catch {};

    switch (term) {
        .Exited => |code| return code == 0,
        else => return false,
    }
}

pub fn interactiveJavaSetup(allocator: mem.Allocator, conf_path: []const u8) error{NeedJava21}!void {
    const text =
        \\Java 21 or newer is required.
        \\
        \\If JAVA_HOME points to an older JDK, it is skipped and the launcher keeps searching.
        \\
        \\YES = open Temurin 21 on GitHub Releases (browser)
        \\NO = pick java.exe / javaw.exe on disk
        \\CANCEL = exit
        \\
        \\After installing from the website, run this app again.
        \\This app never changes your system JAVA_HOME; optional path is saved only in:
        \\%APPDATA%\\dahusim\\desktop-java-home.conf
    ;
    const caption = "Java runtime";

    const answer = MessageBoxA(null, text, caption, MB_YESNOCANCEL | MB_ICONWARNING);
    if (answer == IDCANCEL) return error.NeedJava21;

    if (answer == IDYES) {
        // Keep in sync with buildScript/temurin21-pinned.urls (TEMURIN21_RELEASES_PAGE).
        const url = "https://github.com/adoptium/temurin21-binaries/releases";
        _ = ShellExecuteA(null, "open", url, null, null, 1);
        _ = MessageBoxA(
            null,
            "When the download finishes, install Temurin 21 (Windows x64 JDK MSI or ZIP), then start this application again.",
            caption,
            0,
        );
        return error.NeedJava21;
    }

    if (answer == IDNO) {
        const picked = pickJavaWithPowerShell(allocator, conf_path) catch false;
        if (picked) return;
        _ = MessageBoxA(null, "No valid Java 21 path was saved.", caption, MB_ICONWARNING);
        return error.NeedJava21;
    }

    return error.NeedJava21;
}
