using System;
using System.Collections.Generic;
using System.Linq;
using System.Net;
using System.Net.Http;
using System.Reflection;
using System.Text.Json;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace LivingUnlock.Windows.Services;

public sealed record ReleaseUpdate(string Tag, string Notes, Uri ReleaseUrl, Uri DownloadUrl,
    bool Prerelease, bool IsNewer);

public static class ReleaseUpdateService
{
    public const string ReleasesUrl = "https://github.com/DDZmumo/LivingUnlock/releases";
    private static readonly HttpClient Client = new() { Timeout = TimeSpan.FromSeconds(20), MaxResponseContentBufferSize = 2 * 1024 * 1024 };
    public static string CurrentVersion => (typeof(ReleaseUpdateService).Assembly
        .GetCustomAttribute<AssemblyInformationalVersionAttribute>()?.InformationalVersion ?? "0.3.0").Split('+')[0];

    public static async Task<ReleaseUpdate?> CheckAsync(bool includePrerelease, CancellationToken cancellationToken = default)
    {
        using var request = new HttpRequestMessage(HttpMethod.Get,
            "https://api.github.com/repos/DDZmumo/LivingUnlock/releases?per_page=100");
        request.Headers.UserAgent.ParseAdd("LivingUnlock/" + CurrentVersion);
        request.Headers.Accept.ParseAdd("application/vnd.github+json");
        request.Headers.Add("X-GitHub-Api-Version", "2022-11-28");
        using var response = await Client.SendAsync(request, cancellationToken);
        if (response.StatusCode is HttpStatusCode.Forbidden or HttpStatusCode.TooManyRequests)
            throw new InvalidOperationException("GitHub 暂时限制了请求，请稍后再试。");
        if (!response.IsSuccessStatusCode)
            throw new InvalidOperationException($"GitHub 返回 HTTP {(int)response.StatusCode}，请稍后重试。");
        return SelectRelease(await response.Content.ReadAsStringAsync(cancellationToken), CurrentVersion, includePrerelease);
    }

    public static ReleaseUpdate? SelectRelease(string json, string currentVersion, bool includePrerelease)
    {
        var current = ReleaseVersion.Parse(currentVersion)
            ?? throw new InvalidOperationException("无法识别当前应用版本。");
        using var document = JsonDocument.Parse(json);
        var choices = new List<(ReleaseVersion Version, ReleaseUpdate Release)>();
        foreach (var release in document.RootElement.EnumerateArray())
        {
            if (release.GetProperty("draft").GetBoolean()) continue;
            var tag = release.GetProperty("tag_name").GetString() ?? "";
            var version = ReleaseVersion.Parse(tag);
            if (version is null) continue;
            bool prerelease = release.GetProperty("prerelease").GetBoolean() || version.IsPrerelease;
            if (prerelease && !includePrerelease) continue;
            var page = TrustedUrl(release.GetProperty("html_url").GetString(), "/DDZmumo/LivingUnlock/releases/tag/");
            if (page is null) continue;
            foreach (var asset in release.GetProperty("assets").EnumerateArray())
            {
                string name = asset.GetProperty("name").GetString() ?? "";
                if (!name.Equals($"LivingUnlock-Setup-{version.Text}-x64.exe", StringComparison.OrdinalIgnoreCase)) continue;
                var download = TrustedUrl(asset.GetProperty("browser_download_url").GetString(), "/DDZmumo/LivingUnlock/releases/download/");
                if (download is null || asset.GetProperty("state").GetString() != "uploaded") continue;
                string notes = release.TryGetProperty("body", out var body) ? body.GetString() ?? "" : "";
                choices.Add((version, new ReleaseUpdate(tag, notes, page, download, prerelease, version.CompareTo(current) > 0)));
                break;
            }
        }
        return choices.OrderByDescending(x => x.Version).Select(x => x.Release).FirstOrDefault();
    }

    private static Uri? TrustedUrl(string? value, string pathPrefix)
        => Uri.TryCreate(value, UriKind.Absolute, out var uri) && uri.Scheme == "https" &&
            uri.Host.Equals("github.com", StringComparison.OrdinalIgnoreCase) && uri.IsDefaultPort &&
            string.IsNullOrEmpty(uri.UserInfo) && uri.AbsolutePath.StartsWith(pathPrefix, StringComparison.Ordinal)
            ? uri : null;
}

public sealed class ReleaseVersion : IComparable<ReleaseVersion>
{
    private readonly int[] numbers;
    private readonly string[] suffix;
    public string Text { get; }
    public bool IsPrerelease => suffix.Length > 0;
    private ReleaseVersion(string text, int[] numbers, string[] suffix)
        => (Text, this.numbers, this.suffix) = (text, numbers, suffix);

    public static ReleaseVersion? Parse(string value)
    {
        var match = Regex.Match(value.Trim(), @"^[vV]?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z-]+(?:\.[0-9A-Za-z-]+)*))?(?:\+[0-9A-Za-z.-]+)?$");
        if (!match.Success) return null;
        var numbers = new int[3];
        for (int i = 0; i < 3; ++i)
            if (!int.TryParse(match.Groups[i + 1].Value, out numbers[i])) return null;
        return new ReleaseVersion(value.Trim().TrimStart('v', 'V').Split('+')[0], numbers,
            match.Groups[4].Success ? match.Groups[4].Value.Split('.') : Array.Empty<string>());
    }

    public int CompareTo(ReleaseVersion? other)
    {
        if (other is null) return 1;
        for (int i = 0; i < 3; ++i)
        {
            int c = numbers[i].CompareTo(other.numbers[i]);
            if (c != 0) return c;
        }
        if (!IsPrerelease || !other.IsPrerelease)
            return IsPrerelease == other.IsPrerelease ? 0 : IsPrerelease ? -1 : 1;
        for (int i = 0; i < Math.Min(suffix.Length, other.suffix.Length); ++i)
        {
            bool an = suffix[i].All(char.IsAsciiDigit), bn = other.suffix[i].All(char.IsAsciiDigit);
            int c = an && bn ? CompareNumeric(suffix[i], other.suffix[i]) :
                an != bn ? an ? -1 : 1 : string.CompareOrdinal(suffix[i], other.suffix[i]);
            if (c != 0) return c;
        }
        return suffix.Length.CompareTo(other.suffix.Length);
    }

    private static int CompareNumeric(string a, string b)
    {
        a = a.TrimStart('0'); b = b.TrimStart('0');
        return a.Length != b.Length ? a.Length.CompareTo(b.Length) : string.CompareOrdinal(a, b);
    }
}
