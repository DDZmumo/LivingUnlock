using LivingUnlock.Windows.Services;
using System.Text.Json.Nodes;

static void Require(bool condition, string message)
{
    if (!condition) throw new Exception(message);
}

string fixture = File.ReadAllText(Path.Combine(AppContext.BaseDirectory, "github-releases.json"));
var selected = ReleaseUpdateService.SelectRelease(fixture, "0.2.4", true);
Require(selected?.Tag == "v0.3.0" && selected.IsNewer, "Must choose Windows asset, ignoring drafts and invalid downloads");
Require(ReleaseUpdateService.SelectRelease(fixture, "0.3.0", true)?.IsNewer == false, "Equal version must not update");
Require(ReleaseUpdateService.SelectRelease(fixture, "0.3.1", true)?.IsNewer == false, "Never offer a downgrade");
Require(ReleaseUpdateService.SelectRelease("[]", "0.2.4", true) is null, "Empty release list");
var previewOnly = new JsonArray(JsonNode.Parse(fixture)!.AsArray().Single(x => x!["tag_name"]!.GetValue<string>() == "v0.2.10")!.DeepClone()).ToJsonString();
Require(ReleaseUpdateService.SelectRelease(previewOnly, "0.2.9", false) is null, "Stable channel must skip flagged previews");
Require(ReleaseUpdateService.SelectRelease(previewOnly, "0.2.9", true)?.IsNewer == true, "Numeric version ordering");
Require(ReleaseVersion.Parse("v1.2.0-rc.10")!.CompareTo(ReleaseVersion.Parse("1.2.0-rc.2")) > 0, "Numeric prerelease ordering");
Require(ReleaseVersion.Parse("1.2.0")!.CompareTo(ReleaseVersion.Parse("1.2.0-rc.10")) > 0, "Stable follows prerelease");
Require(ReleaseVersion.Parse("1.2.0+build.2")!.CompareTo(ReleaseVersion.Parse("v1.2.0")) == 0, "Build metadata does not change version");
Require(ReleaseVersion.Parse("invalid") is null, "Invalid tags are rejected");
Console.WriteLine("PASS: Windows release selection, channel filtering, draft/incomplete upload rejection, trusted downloads, semver and downgrade protection");
if (args.Contains("--live"))
{
    var release = await ReleaseUpdateService.CheckAsync(true);
    Require(release is not null, "Expected a Windows release from GitHub");
    Console.WriteLine($"LIVE_GITHUB_OK: {release!.Tag}; {release.DownloadUrl}");
}
