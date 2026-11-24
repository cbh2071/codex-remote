using System.Runtime.InteropServices;
using System.Text;
using System.Text.Json.Nodes;
using System.Text.RegularExpressions;
using Markdig;
using Markdig.Syntax;
using Markdig.Syntax.Inlines;
using Microsoft.Win32.SafeHandles;

namespace CodexRemote;

static class ProjectPreview
{
    static readonly HashSet<string> TextExtensions = new(
        ".txt .md .cs .kt .kts .java .js .jsx .ts .tsx .css .html .xml .json .jsonl .toml .yaml .yml .csv .tsv .py .ps1 .sh .go .rs .c .h .cpp .sql .log .ini .properties .gradle .csproj .diff .patch .tex .r .swift .dart .vue".Split(' '),
        StringComparer.OrdinalIgnoreCase);
    static readonly Dictionary<string, string> Documents = new(StringComparer.OrdinalIgnoreCase) {
        [".png"]="image/png", [".jpg"]="image/jpeg", [".jpeg"]="image/jpeg", [".webp"]="image/webp",
        [".pdf"]="application/pdf", [".docx"]="application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        [".xlsx"]="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        [".pptx"]="application/vnd.openxmlformats-officedocument.presentationml.presentation"
    };

    [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    static extern uint GetFinalPathNameByHandle(SafeFileHandle file, StringBuilder path, uint size, uint flags);

    public static async Task<JsonNode> Read(string cwd, string markdown, string link)
    {
        if (link.Length is 0 or > 2048 || !Markdown.Parse(markdown).Descendants<LinkInline>().Any(x => !x.IsImage && x.Url == link))
            throw new ApiError(403, "当前消息中没有这个文件链接");
        var target = Uri.UnescapeDataString(link).Replace('/', '\\');
        var mark = Regex.Match(target, @"(?:#L|:)([1-9]\d{0,7})(?::\d+|-L\d+)?$");
        int line = mark.Success ? int.Parse(mark.Groups[1].Value) : 0;
        if (mark.Success) target = target[..mark.Index];
        if (Regex.IsMatch(target, @"^\\[a-z]:\\", RegexOptions.IgnoreCase)) target = target[1..];
        return await ReadFile(Resolve(cwd, target), line);
    }
    static string Resolve(string cwd, string target)
    {
        var denial = new ApiError(403, "仅可预览当前项目内的本地文件");
        if (!Path.IsPathFullyQualified(cwd) || cwd.StartsWith('\\') || target.StartsWith('\\') ||
            Regex.IsMatch(target, @"^(?![A-Za-z]:\\).*:") || target.Skip(2).Contains(':') ||
            target.Any(char.IsControl)) throw denial;
        var root = Path.TrimEndingDirectorySeparator(Path.GetFullPath(cwd));
        var prefix = Path.EndsInDirectorySeparator(root) ? root : root + "\\";
        var path = Path.GetFullPath(target, root);
        if (!(target.Length == 0 && path == root) && !path.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) ||
            new DriveInfo(Path.GetPathRoot(path)!).DriveType != DriveType.Fixed) throw denial;
        var relative = path == root ? Array.Empty<string>() : path[prefix.Length..].Split('\\');
        if (relative.Any(p => p.StartsWith('.') || p.EndsWith('.') || p.EndsWith(' ') ||
            Regex.IsMatch(p, @"^(CON|AUX|NUL|PRN|COM[0-9¹²³]|LPT[0-9¹²³])(\.|$)", RegexOptions.IgnoreCase))) throw denial;
        for (FileSystemInfo? part = Directory.Exists(path) ? new DirectoryInfo(path) : new FileInfo(path); part != null; part = part is FileInfo f ? f.Directory : ((DirectoryInfo)part).Parent)
            if (part.Exists && part.Attributes.HasFlag(FileAttributes.ReparsePoint)) throw denial;
        return path;
    }
    public static async Task<JsonNode> Browse(string cwd, string relative, bool list)
    {
        if (relative.Length > 2048 || Path.IsPathRooted(relative) || relative.Split('/', '\\').Any(p => p.StartsWith('.') || p.Contains(':')) ||
            string.IsNullOrWhiteSpace(cwd) || Path.TrimEndingDirectorySeparator(cwd) == Path.GetPathRoot(cwd) ||
            string.Equals(Path.TrimEndingDirectorySeparator(cwd), Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), StringComparison.OrdinalIgnoreCase))
            throw new ApiError(403, "请选择项目工作目录内的文件");
        var path = Resolve(cwd, relative);
        if (!list) return await ReadFile(path, 0);
        if (!Directory.Exists(path)) throw new ApiError(404, "目录不存在");
        var items = new DirectoryInfo(path).EnumerateFileSystemInfos()
            .Where(f => !f.Name.StartsWith('.') && (f.Attributes & (FileAttributes.Hidden | FileAttributes.System | FileAttributes.ReparsePoint)) == 0)
            .OrderByDescending(f => f is DirectoryInfo).ThenBy(f => f.Name, StringComparer.OrdinalIgnoreCase)
            .Select(f => new { name = f.Name, directory = f is DirectoryInfo }).Take(2001).ToList();
        return J.Node(new { entries = items.Take(2000), truncated = items.Count > 2000 });
    }
    static async Task<JsonNode> ReadFile(string path, int line)
    {
        var ext = Path.GetExtension(path);
        var isText = TextExtensions.Contains(ext);
        if (!Documents.TryGetValue(ext, out var mime) && !isText) throw new ApiError(415, "此文件类型暂不支持预览");
        if (!File.Exists(path)) throw new ApiError(404, "文件已移动或不存在");
        using var stream = File.OpenRead(path);
        var resolved = new StringBuilder(32768);
        var length = GetFinalPathNameByHandle(stream.SafeFileHandle, resolved, (uint)resolved.Capacity, 0);
        if (length == 0 || length >= resolved.Capacity || !string.Equals(resolved.ToString(), @"\\?\" + path, StringComparison.OrdinalIgnoreCase)) throw new ApiError(403, "文件路径已变化");
        int limit = isText ? 512 * 1024 : 8 * 1024 * 1024;
        if (stream.Length > limit) throw new ApiError(413, $"预览大小上限为 {limit / 1024} KiB");
        var bytes = new byte[(int)stream.Length];
        await stream.ReadExactlyAsync(bytes);
        string name = Path.GetFileName(path);
        if (!isText) return J.Node(new { name, mime, data = Convert.ToBase64String(bytes) });
        using var reader = new StreamReader(new MemoryStream(bytes), new UTF8Encoding(false, true), true);
        try { return J.Node(new { name, mime = "text/plain", line, text = await reader.ReadToEndAsync() }); }
        catch (DecoderFallbackException) { throw new ApiError(415, "文件编码暂不支持预览"); }
    }
}
