using System.Text.RegularExpressions;

namespace CodexRemote;

sealed class AndroidUpdates(string root)
{
    private const long MaxBytes = 128L * 1024 * 1024;
    private const string ApkType = "application/vnd.android.package-archive";
    private static readonly Regex VersionName = new(@"\A[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}\z");
    private static readonly Regex PackageName = new(@"\Acodex-suixing-([1-9][0-9]{0,9})-([0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5})\.apk\z");
    private DirectoryInfo? OpenChannel(string channel)
    {
        if (channel is not ("stable" or "preview")) return null;
        var parent = new DirectoryInfo(Path.Combine(root, "android-updates"));
        if (!parent.Exists || parent.Attributes.HasFlag(FileAttributes.ReparsePoint)) return null;
        var directory = new DirectoryInfo(Path.Combine(parent.FullName, channel));
        return directory.Exists && !directory.Attributes.HasFlag(FileAttributes.ReparsePoint) ? directory : null;
    }

    private static bool Available(FileInfo file) =>
        file.Exists && !file.Attributes.HasFlag(FileAttributes.ReparsePoint) && file.Length is > 0 and <= MaxBytes;

    public IResult Check(string channel)
    {
        var directory = OpenChannel(channel);
        if (directory is null) return Results.NoContent();
        var release = directory.EnumerateFiles("codex-suixing-*.apk")
            .Select(file => (File: file, Match: PackageName.Match(file.Name)))
            .Where(entry => entry.Match.Success && Available(entry.File))
            .Select(entry => new {
                versionCode = int.TryParse(entry.Match.Groups[1].Value, out var code) ? code : 0,
                versionName = entry.Match.Groups[2].Value,
                size = entry.File.Length
            })
            .Where(entry => entry.versionCode > 0)
            .OrderByDescending(entry => entry.versionCode)
            .ThenBy(entry => entry.versionName, StringComparer.Ordinal)
            .FirstOrDefault();
        return release is null ? Results.NoContent() : Results.Json(release);
    }

    public IResult Download(string channel, int versionCode, string versionName)
    {
        if (versionCode <= 0 || !VersionName.IsMatch(versionName) || OpenChannel(channel) is not { } directory) return Results.NotFound();
        var file = new FileInfo(Path.Combine(directory.FullName, $"codex-suixing-{versionCode}-{versionName}.apk"));
        if (!Available(file)) return Results.NotFound();
        try {
            return Results.File(file.Open(FileMode.Open, FileAccess.Read, FileShare.Read | FileShare.Delete), ApkType);
        }
        catch (FileNotFoundException) { return Results.NotFound(); }
        catch (DirectoryNotFoundException) { return Results.NotFound(); }
    }
}
