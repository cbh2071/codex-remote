using System.Diagnostics;
using System.Net;
using System.Text.Json.Nodes;

namespace CodexRemote;

sealed record TailnetOwner(string Node, string User);
sealed record VerifiedPeer(string Node, string Name);

static class TailnetEnrollment
{
    static readonly SemaphoreSlim slots = new(2);
    static async Task<JsonNode> Cli(params string[] args)
    {
        var path = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ProgramFiles), "Tailscale", "tailscale.exe");
        if (!File.Exists(path)) throw new ApiError(503, "电脑未安装 Tailscale 客户端");
        var start = new ProcessStartInfo(path) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardOutput = true, RedirectStandardError = true };
        foreach (var arg in args) start.ArgumentList.Add(arg);
        using var process = Process.Start(start) ?? throw new ApiError(503, "无法读取 Tailscale 身份");
        using var timeout = new CancellationTokenSource(TimeSpan.FromSeconds(5));
        var stdout = process.StandardOutput.ReadToEndAsync(timeout.Token);
        var stderr = process.StandardError.ReadToEndAsync(timeout.Token);
        try {
            await process.WaitForExitAsync(timeout.Token);
            await stderr;
            if (process.ExitCode == 0) return JsonNode.Parse(await stdout) ?? throw new InvalidDataException();
        } finally { if (!process.HasExited) process.Kill(); }
        throw new ApiError(403, "Tailscale 未能验证这台设备");
    }
    internal static TailnetOwner Owner(JsonNode status)
    {
        var self = status["Self"];
        if (status.S("BackendState") != "Running" || self.S("ID").Length == 0 || !ulong.TryParse(self.S("UserID"), out var user) || user == 0 || self.Array("Tags").Count > 0)
            throw new ApiError(403, "请在电脑上连接个人 Tailscale 节点");
        return new(self.S("ID"), self.S("UserID"));
    }
    public static async Task<TailnetOwner> CurrentOwner() => Owner(await Cli("status", "--json"));
    internal static VerifiedPeer Verify(TailnetOwner owner, JsonNode status, JsonNode who, IPAddress remote, IPAddress local)
    {
        var node = who["Node"];
        if (Owner(status) != owner || !PrivateNetwork.IsTailscale(local) || !PrivateNetwork.IsTailscale(remote)
            || !status["Self"].Array("TailscaleIPs").Any(ip => ip?.ToString() == local.ToString())
            || node.S("User") != owner.User || who["UserProfile"].S("ID") != owner.User || node.Array("Tags").Count > 0
            || node.S("StableID").Length == 0 || node.S("StableID") == owner.Node
            || !node.Array("Addresses").Any(ip => ip?.ToString() == remote + "/32"))
            throw new ApiError(403, "此电脑只允许同一 Tailscale 账号的个人设备免扫码连接");
        return new(node.S("StableID"), node.S("ComputedName", "Tailscale 设备"));
    }
    public static async Task<VerifiedPeer> Identify(TailnetOwner owner, IPAddress remote, int port, IPAddress local)
    {
        if (!await slots.WaitAsync(0)) throw new ApiError(429, "正在验证其他设备，请稍后再试");
        try {
            var status = await Cli("status", "--json");
            var who = await Cli("whois", "--json", new IPEndPoint(remote, port).ToString());
            return Verify(owner, status, who, remote, local);
        } finally { slots.Release(); }
    }
}
