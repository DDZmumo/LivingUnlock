using System;
using System.Net.Http;
using System.Threading;
using System.Threading.Tasks;
using LivingUnlock.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;

namespace LivingUnlock.Windows.Views;

public sealed partial class UpdateDialog : ContentDialog
{
    private readonly CancellationTokenSource lifetime = new();
    private Uri? actionUrl;
    private bool checking;

    public UpdateDialog()
    {
        InitializeComponent();
        VersionText.Text = $"当前版本：{ReleaseUpdateService.CurrentVersion}";
        Closed += (_, _) => lifetime.Cancel();
    }

    private async void OnLoaded(object sender, RoutedEventArgs e) => await CheckAsync();
    private async void OnCheckClicked(object sender, RoutedEventArgs e) => await CheckAsync();

    private void OnChannelChanged(object sender, RoutedEventArgs e)
    {
        if (ResultBar is null || checking) return;
        IsPrimaryButtonEnabled = false;
        actionUrl = null;
        NotesText.Text = "";
        ResultBar.Severity = InfoBarSeverity.Informational;
        ResultBar.Message = "版本范围已切换，请重新检查。";
    }

    private async Task CheckAsync()
    {
        if (checking) return;
        checking = true;
        CheckButton.IsEnabled = IncludePrerelease.IsEnabled = false;
        CheckingRing.IsActive = true;
        IsPrimaryButtonEnabled = false;
        actionUrl = null;
        NotesText.Text = "";
        ResultBar.Severity = InfoBarSeverity.Informational;
        ResultBar.Message = "正在连接 GitHub…";
        try
        {
            var release = await ReleaseUpdateService.CheckAsync(IncludePrerelease.IsChecked == true, lifetime.Token);
            if (lifetime.IsCancellationRequested) return;
            if (release is null)
            {
                ResultBar.Message = "所选范围内暂无 Windows x64 安装包。可尝试包含预发布版本。";
                return;
            }
            ResultBar.Severity = release.IsNewer ? InfoBarSeverity.Success : InfoBarSeverity.Informational;
            ResultBar.Message = release.IsNewer
                ? $"发现新版本 {release.Tag}{(release.Prerelease ? "（预发布）" : "")}"
                : $"当前无需更新。GitHub 上此平台最新版本为 {release.Tag}{(release.Prerelease ? "（预发布）" : "")}。";
            NotesText.Text = string.IsNullOrWhiteSpace(release.Notes) ? "此版本没有更新说明。" : release.Notes[..Math.Min(6000, release.Notes.Length)];
            actionUrl = release.IsNewer ? release.DownloadUrl : release.ReleaseUrl;
            PrimaryButtonText = release.IsNewer ? "下载安装包" : "查看此版本";
            IsPrimaryButtonEnabled = true;
        }
        catch (OperationCanceledException) when (lifetime.IsCancellationRequested) { }
        catch (Exception ex)
        {
            ResultBar.Severity = InfoBarSeverity.Warning;
            ResultBar.Message = ex is OperationCanceledException ? "检查超时，请重试。" :
                ex is HttpRequestException ? "无法连接 GitHub，请检查网络后重试。" : ex.Message;
        }
        finally
        {
            checking = false;
            CheckButton.IsEnabled = IncludePrerelease.IsEnabled = true;
            CheckingRing.IsActive = false;
        }
    }

    private async void OnDownloadClicked(ContentDialog sender, ContentDialogButtonClickEventArgs args)
    {
        var deferral = args.GetDeferral();
        try
        {
            if (actionUrl is null || !await global::Windows.System.Launcher.LaunchUriAsync(actionUrl))
            {
                args.Cancel = true;
                ResultBar.Severity = InfoBarSeverity.Warning;
                ResultBar.Message = "无法打开浏览器，请使用下方 GitHub Releases 链接。";
            }
        }
        finally { deferral.Complete(); }
    }
}
