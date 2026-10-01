using System;
using System.Threading.Tasks;
using LivingUnlock.Windows.Models;
using LivingUnlock.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace LivingUnlock.Windows.Views;

public sealed partial class LivingUnlockPairPage : Page
{
    private AccountInfo _account = new();
    private readonly PairingProcessManager _pairingManager = new();
    private DispatcherTimer? _countdownTimer;
    private int _secondsLeft = 180;
    private bool _isActive;
    private bool _starting;
    private bool _showAlreadyPairedDialog;

    public LivingUnlockPairPage()
    {
        InitializeComponent();
        _pairingManager.PairingUriReceived += OnPairingUriReceived;
        _pairingManager.PairingSucceeded += OnPairingSucceeded;
        _pairingManager.PairingFailed += OnPairingFailed;
        _pairingManager.PairingCancelled += OnPairingCancelled;
    }

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        _isActive = true;
        if (e.Parameter is AccountInfo account) _account = account;
        TxtTargetAccount.Text = _account.QualifiedUsername;
        await StartPairingSessionAsync();
    }

    protected override void OnNavigatedFrom(NavigationEventArgs e)
    {
        _isActive = false;
        _showAlreadyPairedDialog = false;
        StopPairingSession();
        base.OnNavigatedFrom(e);
    }

    private async void OnPageLoaded(object sender, RoutedEventArgs e)
    {
        PageLayout.FitContent(PageScrollViewer, ContentHost, PageContent, 1080);
        Motion.Enter(PairingPanel, 0, 18);
        Motion.Enter(GuidePanel, 65, 18);
        await ShowAlreadyPairedDialogAsync();
    }

    private void OnScrollViewportSizeChanged(object sender, SizeChangedEventArgs e)
        => PageLayout.FitContent(PageScrollViewer, ContentHost, PageContent, 1080);

    private async Task StartPairingSessionAsync()
    {
        if (_starting) return;
        _starting = true;
        try
        {
            StopPairingSession();
            ResetUi();
            TxtPulsingStatus.Text = "正在检查已有手机绑定…";
            TxtCountdown.Text = string.Empty;
            bool paired = MainWindow.Current.Vault.IsPhonePaired();
            if (!paired)
            {
                // Installed system records may not be readable without elevation.
                var state = await UnlockConfigurationManager.ReadStateAsync();
                paired = state.PhoneConfigured;
            }
            if (!_isActive) return;
            if (paired)
            {
                QrLoadingRing.IsActive = false;
                PulseRing.IsActive = false;
                PairingPanel.Visibility = Visibility.Collapsed;
                AlreadyPairedPanel.Visibility = Visibility.Visible;
                GuidePanel.Visibility = Visibility.Collapsed;
                _showAlreadyPairedDialog = true;
                await ShowAlreadyPairedDialogAsync();
                return;
            }
            await _pairingManager.StartPairingAsync();
        }
        catch (Exception ex)
        {
            if (!_isActive) return;
            QrLoadingRing.IsActive = false;
            PulseRing.IsActive = false;
            TxtPulsingStatus.Text = "未能确认当前绑定状态";
            TxtCountdown.Text = string.Empty;
            ShowInfo("无法检查绑定", ex.Message, InfoBarSeverity.Error);
        }
        finally { _starting = false; }
    }

    private async Task ShowAlreadyPairedDialogAsync()
    {
        if (!_showAlreadyPairedDialog || !_isActive || XamlRoot is null) return;
        _showAlreadyPairedDialog = false;
        var dialog = new ContentDialog
        {
            Title = "不可重复配对",
            Content = "当前 Windows 账户已经绑定手机，请先回到主页解除蓝牙绑定，再配对新手机。",
            CloseButtonText = "知道了",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot
        };
        await dialog.ShowAsync();
    }

    private void StopPairingSession()
    {
        _countdownTimer?.Stop();
        _countdownTimer = null;
        _pairingManager.Stop();
    }

    private void ResetUi()
    {
        PairingPanel.Visibility = Visibility.Visible;
        AlreadyPairedPanel.Visibility = Visibility.Collapsed;
        GuidePanel.Visibility = Visibility.Visible;
        QrLoadingRing.IsActive = true;
        QrLoadingRing.Visibility = Visibility.Visible;
        QrContainer.Visibility = Visibility.Collapsed;
        SuccessOverlay.Visibility = Visibility.Collapsed;
        CardPairSuccess.Visibility = Visibility.Collapsed;
        PairInfoBar.IsOpen = false;
        PulseRing.IsActive = true;
        TxtPulsingStatus.Text = "正在启动本地配对服务…";
        _secondsLeft = 180;
        TxtCountdown.Text = "二维码有效期 180 秒";
    }

    private async void OnPairingUriReceived(string uri)
    {
        DispatcherQueue.TryEnqueue(async () =>
        {
            try
            {
                ImgQrCode.Source = await QrCodeHelper.GenerateQrBitmapAsync(uri, 8);
                QrLoadingRing.Visibility = Visibility.Collapsed;
                QrContainer.Visibility = Visibility.Visible;
                TxtPulsingStatus.Text = "等待 Android 扫码连接…";
                StartCountdown();
            }
            catch (Exception ex)
            {
                ShowInfo("二维码渲染失败", ex.Message, InfoBarSeverity.Error);
            }
        });
        await Task.CompletedTask;
    }

    private void StartCountdown()
    {
        _countdownTimer?.Stop();
        _secondsLeft = 180;
        _countdownTimer = new DispatcherTimer { Interval = TimeSpan.FromSeconds(1) };
        _countdownTimer.Tick += (_, _) =>
        {
            _secondsLeft--;
            TxtCountdown.Text = _secondsLeft > 0 ? $"二维码有效期 {_secondsLeft} 秒" : "二维码已过期";
            if (_secondsLeft > 0) return;
            _countdownTimer.Stop();
            PulseRing.IsActive = false;
            TxtPulsingStatus.Text = "配对码已过期，请刷新";
        };
        _countdownTimer.Start();
    }

    private void OnPairingSucceeded(string deviceName)
    {
        DispatcherQueue.TryEnqueue(async () =>
        {
            _countdownTimer?.Stop();
            TxtPulsingStatus.Text = "正在启用锁屏蓝牙解锁…";
            try
            {
                var saved = MainWindow.Current.Vault.SaveUnlockCredentials(_account);
                if (!saved.Success) throw new InvalidOperationException(saved.ErrorMessage);
                await UnlockConfigurationManager.RunAsync("promote-pair");
                SuccessOverlay.Visibility = Visibility.Visible;
                CardPairSuccess.Visibility = Visibility.Visible;
                PulseRing.IsActive = false;
                TxtPulsingStatus.Text = "设备绑定成功";
                TxtSuccessDeviceName.Text = $"已绑定设备：{deviceName}";
                ShowInfo("绑定完成", "手机公钥与 Windows 凭据已保存到锁屏组件。", InfoBarSeverity.Success);
            }
            catch (Exception ex)
            {
                PulseRing.IsActive = false;
                TxtPulsingStatus.Text = "尚未在锁屏启用";
                ShowInfo("配对未完成", ex.Message, InfoBarSeverity.Error);
            }
        });
    }

    private void OnPairingFailed(string error)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            _countdownTimer?.Stop();
            PulseRing.IsActive = false;
            TxtPulsingStatus.Text = "配对失败";
            ShowInfo("无法完成配对", error, InfoBarSeverity.Error);
        });
    }

    private void OnPairingCancelled(string reason)
    {
        DispatcherQueue.TryEnqueue(() =>
        {
            if (!_isActive) return;
            _countdownTimer?.Stop();
            _secondsLeft = 0;
            PulseRing.IsActive = false;
            QrLoadingRing.IsActive = false;
            QrLoadingRing.Visibility = Visibility.Collapsed;
            QrContainer.Visibility = Visibility.Collapsed;
            ImgQrCode.Source = null;
            TxtPulsingStatus.Text = "手机连接已结束";
            TxtCountdown.Text = "本次二维码已停止使用";
            ShowInfo("配对会话已取消", "尚未收到配对请求，手机连接已断开。若正在解除绑定，可忽略此提示；需要重新配对时，请刷新二维码后扫码。", InfoBarSeverity.Informational);
        });
    }

    private async void OnRefreshClicked(object sender, RoutedEventArgs e) => await StartPairingSessionAsync();

    private void ShowInfo(string title, string message, InfoBarSeverity severity)
    {
        PairInfoBar.Title = title;
        PairInfoBar.Message = message;
        PairInfoBar.Severity = severity;
        PairInfoBar.IsOpen = true;
    }

    private void OnBackClicked(object sender, RoutedEventArgs e)
    {
        StopPairingSession();
        MainWindow.Current.NavigateToHome();
    }
}
