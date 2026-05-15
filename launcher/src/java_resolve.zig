const std = @import("std");
const builtin = @import("builtin");
const fs = std.fs;
const mem = std.mem;
const process = std.process;
const native_os = builtin.os.tag;

const win = switch (native_os) {
    .windows => @import("java_resolve_win.zig"),
    else => struct {
        pub fn interactiveJavaSetup(_: mem.Allocator, _: []const u8) error{NeedJava21}!void {
            return error.NeedJava21;
        }
    },
};

pub fn javaOutputShowsMajorAtLeast21(text: []const u8) bool {
    const needle = "version \"";
    var pos: usize = 0;
    while (mem.indexOfPos(u8, text, pos, needle)) |i| {
        const start = i + needle.len;
        if (start >= text.len) return false;
        var end = start;
        while (end < text.len and std.ascii.isDigit(text[end])) end += 1;
        if (end == start) {
            pos = start;
            continue;
        }
        const major = std.fmt.parseInt(u32, text[start..end], 10) catch {
            pos = start;
            continue;
        };
        if (major >= 21) return true;
        pos = end;
    }
    return false;
}

fn fileExists(path: []const u8) bool {
    fs.accessAbsolute(path, .{}) catch return false;
    return true;
}

fn readFirstConfigLine(allocator: mem.Allocator, path: []const u8) !?[]const u8 {
    if (!fileExists(path)) return null;
    const file = try fs.openFileAbsolute(path, .{});
    defer file.close();
    const max = 4096;
    const buf = try file.readToEndAlloc(allocator, max);
    defer allocator.free(buf);
    var it = mem.tokenizeAny(u8, buf, "\r\n");
    while (it.next()) |line| {
        const t = mem.trim(u8, line, &std.ascii.whitespace);
        if (t.len == 0 or t[0] == '#') continue;
        return try allocator.dupe(u8, t);
    }
    return null;
}

fn resolveJavaHomeCommand(allocator: mem.Allocator, java_home: []const u8) !?[]const u8 {
    if (java_home.len == 0) return null;

    const candidate_names = if (native_os == .windows)
        [_][]const u8{ "javaw.exe", "java.exe" }
    else
        [_][]const u8{"java"};

    for (candidate_names) |candidate_name| {
        const candidate = try fs.path.join(allocator, &.{ java_home, "bin", candidate_name });
        if (fileExists(candidate)) {
            return candidate;
        }
        allocator.free(candidate);
    }
    return null;
}

fn candidateFromLine(allocator: mem.Allocator, line: []const u8) !?[]const u8 {
    const t = mem.trim(u8, line, &std.ascii.whitespace);
    if (t.len == 0) return null;

    if (native_os == .windows) {
        const lower = try std.ascii.allocLowerString(allocator, t);
        defer allocator.free(lower);
        if (mem.endsWith(u8, lower, ".exe") and fileExists(t)) {
            return try allocator.dupe(u8, t);
        }
    } else if (fileExists(t)) {
        return try allocator.dupe(u8, t);
    }

    return try resolveJavaHomeCommand(allocator, t);
}

fn javaProbeBinary(allocator: mem.Allocator, java_cmd: []const u8) ![]const u8 {
    if (native_os == .windows and mem.endsWith(u8, java_cmd, "javaw.exe")) {
        const dir = fs.path.dirname(java_cmd) orelse return try allocator.dupe(u8, java_cmd);
        const probe = try fs.path.join(allocator, &.{ dir, "java.exe" });
        if (fileExists(probe)) return probe;
        allocator.free(probe);
    }
    return try allocator.dupe(u8, java_cmd);
}

pub fn verifyJava21OrNewer(allocator: mem.Allocator, java_cmd: []const u8) !bool {
    const probe = try javaProbeBinary(allocator, java_cmd);
    defer allocator.free(probe);

    const result = process.Child.run(.{
        .allocator = allocator,
        .argv = &.{ probe, "-version" },
    }) catch return false;
    defer allocator.free(result.stdout);
    defer allocator.free(result.stderr);

    const combined = try std.fmt.allocPrint(allocator, "{s}{s}", .{ result.stderr, result.stdout });
    defer allocator.free(combined);

    switch (result.term) {
        .Exited => |code| {
            if (code != 0) return false;
        },
        else => return false,
    }
    return javaOutputShowsMajorAtLeast21(combined);
}

fn resolveMacOSJavaHome(allocator: mem.Allocator) !?[]const u8 {
    if (native_os != .macos) return null;

    const result = process.Child.run(.{
        .allocator = allocator,
        .argv = &.{ "/usr/libexec/java_home", "-v", "21" },
    }) catch |err| switch (err) {
        error.FileNotFound => return null,
        else => |e| return e,
    };
    defer allocator.free(result.stdout);
    defer allocator.free(result.stderr);

    switch (result.term) {
        .Exited => |code| if (code != 0) return null,
        else => return null,
    }

    const java_home = mem.trim(u8, result.stdout, &std.ascii.whitespace);
    if (java_home.len == 0) return null;

    const java_bin = try std.fmt.allocPrint(allocator, "{s}/bin/java", .{java_home});
    if (fileExists(java_bin)) return java_bin;

    allocator.free(java_bin);
    return null;
}

fn tryWindowsHeuristic(allocator: mem.Allocator) !?[]const u8 {
    if (native_os != .windows) return null;

    const static_bases = [_][]const u8{
        "C:\\Program Files\\Eclipse Adoptium",
        "C:\\Program Files\\Java",
        "C:\\Program Files\\Microsoft",
        "C:\\Program Files\\Zulu",
        "C:\\Program Files (x86)\\Java",
        "C:\\Program Files (x86)\\Eclipse Adoptium",
        "C:\\Program Files (x86)\\Microsoft",
        "C:\\Program Files (x86)\\Zulu",
    };

    var env_bases: [3]?[]const u8 = .{ null, null, null };
    if (process.getEnvVarOwned(allocator, "ProgramW6432") catch null) |p| {
        env_bases[0] = p;
    }
    if (process.getEnvVarOwned(allocator, "ProgramFiles") catch null) |p| {
        env_bases[1] = p;
    }
    if (process.getEnvVarOwned(allocator, "ProgramFiles(x86)") catch null) |p| {
        env_bases[2] = p;
    }
    defer {
        if (env_bases[0]) |s| allocator.free(s);
        if (env_bases[1]) |s| allocator.free(s);
        if (env_bases[2]) |s| allocator.free(s);
    }

    for (env_bases) |maybe| {
        if (maybe) |base| {
            if (try scanJdk21Under(allocator, base)) |c| return c;
        }
    }

    for (static_bases) |base| {
        if (try scanJdk21Under(allocator, base)) |c| return c;
    }

    return null;
}

fn scanJdk21Under(allocator: mem.Allocator, base: []const u8) !?[]const u8 {
    var dir = fs.openDirAbsolute(base, .{ .iterate = true }) catch return null;
    defer dir.close();

    var it = dir.iterate();
    while (try it.next()) |entry| {
        if (entry.kind != .directory) continue;
        const name = entry.name;
        if (!containsJdk21(name)) continue;
        const child = try fs.path.join(allocator, &.{ base, name, "bin", "javaw.exe" });
        if (fileExists(child)) return child;
        allocator.free(child);
        const child_java = try fs.path.join(allocator, &.{ base, name, "bin", "java.exe" });
        if (fileExists(child_java)) return child_java;
        allocator.free(child_java);
    }
    return null;
}

fn containsJdk21(name: []const u8) bool {
    if (mem.indexOf(u8, name, "jdk-21") != null) return true;
    if (mem.indexOf(u8, name, "jdk21") != null) return true;
    if (mem.indexOf(u8, name, "jdk-22") != null) return true;
    if (mem.indexOf(u8, name, "jdk-23") != null) return true;
    if (mem.indexOf(u8, name, "jdk-24") != null) return true;
    if (mem.indexOf(u8, name, "jdk-25") != null) return true;
    return false;
}

pub fn resolveJavaExecutable(allocator: mem.Allocator, java_home_conf_path: []const u8) ![]const u8 {
    while (true) {
        if (try readFirstConfigLine(allocator, java_home_conf_path)) |line| {
            defer allocator.free(line);
            if (try candidateFromLine(allocator, line)) |c| {
                if (try verifyJava21OrNewer(allocator, c)) return c;
                allocator.free(c);
            }
        }

        if (process.getEnvVarOwned(allocator, "JAVA_HOME") catch null) |java_home| {
            defer allocator.free(java_home);
            if (try resolveJavaHomeCommand(allocator, java_home)) |c| {
                if (try verifyJava21OrNewer(allocator, c)) return c;
                allocator.free(c);
            }
        }

        if (process.getEnvVarOwned(allocator, "JAVA") catch null) |java_env| {
            defer allocator.free(java_env);
            if (java_env.len > 0 and try verifyJava21OrNewer(allocator, java_env)) {
                return try allocator.dupe(u8, java_env);
            }
        }

        if (native_os == .windows) {
            if (try tryWindowsHeuristic(allocator)) |c| {
                if (try verifyJava21OrNewer(allocator, c)) return c;
                allocator.free(c);
            }
        }

        if (try resolveMacOSJavaHome(allocator)) |c| {
            if (try verifyJava21OrNewer(allocator, c)) return c;
            allocator.free(c);
        }

        const fallback = try allocator.dupe(u8, "java");
        if (try verifyJava21OrNewer(allocator, fallback)) return fallback;
        allocator.free(fallback);

        if (native_os == .windows) {
            try win.interactiveJavaSetup(allocator, java_home_conf_path);
            continue;
        }

        std.debug.print(
            "Java 21+ required. Set JAVA_HOME, JAVA, or the first line of:\n{s}\n",
            .{java_home_conf_path},
        );
        return error.NeedJava21;
    }
}
