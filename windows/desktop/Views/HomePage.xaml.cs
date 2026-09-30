using System;
using LivingUnlock.Windows.Models;
using LivingUnlock.Windows.Services;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Navigation;

namespace LivingUnlock.Windows.Views;

public sealed partial class HomePage : Page
{
    private UnlockState? _currentState;
    private bool _locationReady;
    private bool _locationBusy;

    public HomePage()
    {
        InitializeComponent();
        LocationToggle.IsOn = BootLocationService.Enabled;
        _locationReady = true;
    }

    private async void OnLocationToggled(object sender, RoutedEventArgs e)
    {
        if (!_locationReady || _locationBusy) return;
        _locationBusy = true;
        LocationToggle.IsEnabled = false;
        try
        {
            if (LocationToggle.IsOn)
            {
                await BootLocationService.EnableAsync();
                LocationStatusText.Text = "已启用。下次登录时采集位置；采集不到则显示未记录，不回填当前地点作为历史位置。";
            }
            else
            {
                BootLocationService.Disable();
                LocationStatusText.Text = "已关闭，电脑已保存的位置已清除；手机端在下次同步时清除旧位置。";
            }
        }
        catch (Exception ex)
        {
            LocationToggle.IsOn = BootLocationService.Enabled;
            LocationStatusText.Text = ex.Message;
        }
        finally { _locationBusy = false; LocationToggle.IsEnabled = true; }
    }

    private async void OnLocationSettingsClicked(object sender, RoutedEventArgs e)
        => await global::Windows.System.Launcher.LaunchUriAsync(new Uri("ms-settings:privacy-location"));

    protected override async void OnNavigatedTo(NavigationEventArgs e)
    {
        base.OnNavigatedTo(e);
        var vault = MainWindow.Current.Vault;
        TxtComputerName.Text = vault.ComputerName;
        var detectedAccount = vault.DetectCurrentAccountIdentity();
        TxtCurrentUsername.Text = detectedAccount?.Username ?? vault.CurrentUsername;

        var account = MainWindow.Current.CurrentAccount;
        if (account is not null && !string.IsNullOrWhiteSpace(account.Username))
        {
            if (account.Type == AccountType.MicrosoftAccount)
            {
                RadioMicrosoftAccount.IsChecked = true;
            }
            else
            {
                RadioLocalUser.IsChecked = true;
            }
            TxtUsername.Text = account.Username;
        }
        else
        {
            account = detectedAccount ?? vault.GetSavedAccountIdentity() ?? new AccountInfo();
            MainWindow.Current.CurrentAccount = account;
            if (!string.IsNullOrWhiteSpace(account.Username))
            {
                if (account.Type == AccountType.MicrosoftAccount) RadioMicrosoftAccount.IsChecked = true;
                else RadioLocalUser.IsChecked = true;
            }
            else
            {
                RadioLocalUser.IsChecked = false;
                RadioMicrosoftAccount.IsChecked = false;
                LblUsername.Text = "Windows 账户名称";
                TxtUsername.PlaceholderText = "请选择账户类型后填写";
            }
            TxtUsername.Text = account.Username;
        }
        DetectedAccountText.Text = detectedAccount is null
            ? string.IsNullOrWhiteSpace(account.Username)
                ? "未能确认当前账户类型，请手动选择本地用户或 Microsoft 账户。"
                : "未能确认当前账户类型，已保留保存的账户信息，可手动调整。"
            : detectedAccount.Type == AccountType.MicrosoftAccount
                ? "当前登录为 Microsoft 账户，已获取关联邮箱。"
                : "当前登录为本地账户，已获取用户名。";
        TxtPassword.PasswordRevealMode = PasswordRevealMode.Hidden;
        IconRevealPassword.Glyph = "\uE7B3";
        TxtRevealPassword.Text = "显示密码";
        await RefreshStatusAsync();
    }

    private void OnPageLoaded(object sender, RoutedEventArgs e)
    {
        PageLayout.FitContent(PageScrollViewer, ContentHost, PageContent, 1120);
        Motion.Enter(HeroPanel, 0, 16);
        Motion.Enter(AccountPanel, 55, 16);
        Motion.Enter(UnlockModesCard, 105, 16);
        Motion.Enter(ManagementCard, 155, 16);
    }

    private void OnScrollViewportSizeChanged(object sender, SizeChangedEventArgs e)
        => PageLayout.FitContent(PageScrollViewer, ContentHost, PageContent, 1120);

    private async System.Threading.Tasks.Task RefreshStatusAsync()
    {
        ManagedStateText.Text = "正在读取锁屏组件的启用状态…";
        _currentState = null;
        BtnRemovePhone.IsEnabled = BtnRemoveAuth.IsEnabled = BtnRemoveBoth.IsEnabled = false;
        BtnTogglePhone.IsEnabled = BtnToggleAuth.IsEnabled = false;
        try
        {
            var state = await UnlockConfigurationManager.ReadStateAsync();
            _currentState = state;
            TxtPhoneStatus.Text = !state.PhoneConfigured ? "未绑定" : state.PhoneEnabled ? "已启用" : "已禁用";
            TxtAuthStatus.Text = !state.AuthenticatorConfigured ? "未绑定" : state.AuthenticatorEnabled ? "已启用" : "已禁用";
            TxtPhoneStatus.Foreground = StatusBrush(state.PhoneEnabled);
            TxtAuthStatus.Foreground = StatusBrush(state.AuthenticatorEnabled);
            BtnTogglePhone.IsEnabled = BtnRemovePhone.IsEnabled = state.PhoneConfigured;
            BtnToggleAuth.IsEnabled = BtnRemoveAuth.IsEnabled = state.AuthenticatorConfigured;
            BtnTogglePhone.Content = state.PhoneEnabled ? "禁用蓝牙" : "启用蓝牙";
            BtnToggleAuth.Content = state.AuthenticatorEnabled ? "禁用动态码" : "启用动态码";
            BtnRemoveBoth.IsEnabled = state.PhoneConfigured || state.AuthenticatorConfigured;
            ManagedStateText.Text = $"蓝牙：{TxtPhoneStatus.Text}  ·  Authenticator：{TxtAuthStatus.Text}";
            AccountStateText.Text = MainWindow.Current.Vault.GetSavedAccountIdentity() is not null
                ? "账户密码已加密保存，可直接使用左侧绑定向导。"
                : state.PhoneConfigured || state.AuthenticatorConfigured
                    ? "当前账户已有解锁方式。更新密码后请重新保存相应凭据。"
                    : "录入当前账户密码后，即可选择下方任一绑定方式。";
        }
        catch (Exception ex)
        {
            TxtPhoneStatus.Text = TxtAuthStatus.Text = "状态未验证";
            ManagedStateText.Text = "读取系统状态需要管理员确认。";
            ShowInfo("状态读取失败", ex.Message, InfoBarSeverity.Warning);
        }
    }

    private static Microsoft.UI.Xaml.Media.SolidColorBrush StatusBrush(bool enabled)
        => new(enabled
            ? global::Windows.UI.Color.FromArgb(255, 104, 245, 178)
            : global::Windows.UI.Color.FromArgb(255, 158, 176, 192));

    private void OnAccountTypeChanged(object sender, RoutedEventArgs e)
    {
        if (RadioMicrosoftAccount is null || LblUsername is null || TxtUsername is null) return;
        var microsoft = RadioMicrosoftAccount.IsChecked == true;
        LblUsername.Text = microsoft ? "Microsoft 账户邮箱" : "本地账户用户名";
        TxtUsername.PlaceholderText = microsoft ? "name@example.com" : MainWindow.Current.Vault.CurrentUsername;
        if (microsoft && !TxtUsername.Text.Contains('@')) TxtUsername.Text = string.Empty;
        if (!microsoft && string.IsNullOrWhiteSpace(TxtUsername.Text)) TxtUsername.Text = MainWindow.Current.Vault.CurrentUsername;
    }

    private AccountInfo BuildAccount() => new()
    {
        Type = RadioMicrosoftAccount.IsChecked == true ? AccountType.MicrosoftAccount : AccountType.LocalUser,
        Username = TxtUsername.Text.Trim(),
        Password = TxtPassword.Password
    };

    private bool ValidateAccount(AccountInfo account)
    {
        if (RadioLocalUser.IsChecked != true && RadioMicrosoftAccount.IsChecked != true)
        {
            ShowInfo("需要账户类型", "请选择本地用户或 Microsoft 账户。", InfoBarSeverity.Warning);
            return false;
        }
        if (string.IsNullOrWhiteSpace(account.Username))
        {
            ShowInfo("需要账户名称", "请选择账户类型并输入本地用户名或 Microsoft 账户邮箱。", InfoBarSeverity.Warning);
            TxtUsername.Focus(FocusState.Programmatic);
            return false;
        }
        if (string.IsNullOrEmpty(account.Password))
        {
            ShowInfo("需要 Windows 密码", "请输入当前账户密码后再进入绑定向导。", InfoBarSeverity.Warning);
            TxtPassword.Focus(FocusState.Programmatic);
            return false;
        }
        return true;
    }

    public AccountInfo? GetBindingAccount()
    {
        var account = BuildAccount();
        if (string.IsNullOrEmpty(account.Password))
        {
            var saved = MainWindow.Current.Vault.GetSavedAccountForBinding();
            if (saved is not null && saved.Type == account.Type &&
                string.Equals(saved.Username, account.Username, StringComparison.OrdinalIgnoreCase))
                account.Password = saved.Password;
        }
        if (!ValidateAccount(account)) return null;
        MainWindow.Current.CurrentAccount = account;
        return account;
    }

    private void OnTogglePasswordRevealClicked(object sender, RoutedEventArgs e)
    {
        if (TxtPassword.PasswordRevealMode == PasswordRevealMode.Visible)
        {
            TxtPassword.PasswordRevealMode = PasswordRevealMode.Hidden;
            IconRevealPassword.Glyph = "\uE7B3";
            TxtRevealPassword.Text = "显示密码";
        }
        else
        {
            TxtPassword.PasswordRevealMode = PasswordRevealMode.Visible;
            IconRevealPassword.Glyph = "\uED1A";
            TxtRevealPassword.Text = "隐藏密码";
        }
    }

    private void OnAuthModeTapped(object sender, Microsoft.UI.Xaml.Input.TappedRoutedEventArgs e)
    {
        var account = GetBindingAccount();
        if (account is null) return;
        MainWindow.Current.NavigateToAuthenticator(account);
    }

    private void OnPhoneModeTapped(object sender, Microsoft.UI.Xaml.Input.TappedRoutedEventArgs e)
    {
        var account = GetBindingAccount();
        if (account is null) return;
        MainWindow.Current.NavigateToPairing(account);
    }

    private async void OnSaveCredentialsClicked(object sender, RoutedEventArgs e)
    {
        var account = BuildAccount();
        if (!ValidateAccount(account)) return;
        var result = MainWindow.Current.Vault.SaveUnlockCredentials(account);
        if (result.Success)
        {
            try
            {
                await UnlockConfigurationManager.RunAsync("promote-credentials");
                TxtPassword.Password = string.Empty;
                TxtPassword.PasswordRevealMode = PasswordRevealMode.Hidden;
                IconRevealPassword.Glyph = "\uE7B3";
                TxtRevealPassword.Text = "显示密码";
                MainWindow.Current.CurrentAccount = new AccountInfo
                {
                    Type = account.Type,
                    Username = account.Username
                };
                ShowInfo("蓝牙解锁凭据已保存", "锁屏组件现可读取当前账户的登录凭据。现在可使用左侧导航或卡片进入绑定向导。", InfoBarSeverity.Success);
                await RefreshStatusAsync();
            }
            catch (Exception ex) { ShowInfo("保存尚未生效", ex.Message, InfoBarSeverity.Error); }
        }
        else ShowInfo("保存失败", result.ErrorMessage, InfoBarSeverity.Error);
    }

    private async void OnUnpairPhoneClicked(object sender, RoutedEventArgs e)
    {
        await RemoveMethodAsync("remove-phone", "卸载蓝牙解锁？",
            "这会移除当前账户的手机配对与蓝牙登录凭据。Authenticator 和原生 PIN 保留。");
    }

    private async void OnRemoveAuthClicked(object sender, RoutedEventArgs e)
    {
        await RemoveMethodAsync("remove-auth", "卸载 Authenticator 解锁？",
            "这会移除当前账户的动态码密钥。蓝牙解锁与原生 PIN 保留。");
    }

    private async void OnRemoveBothClicked(object sender, RoutedEventArgs e)
    {
        await RemoveMethodAsync("remove-both", "卸载两种 LivingUnlock 解锁方式？",
            "这会移除当前账户的手机配对、动态码密钥和蓝牙登录凭据。原生 PIN 保留。");
    }

    private async void OnDeleteCredentialsClicked(object sender, RoutedEventArgs e)
    {
        BtnDeleteCredentials.IsEnabled = false;
        try
        {
            await RemoveMethodAsync("remove-both", "删除凭证及全部绑定？",
                "这会清除当前 Windows 用户保存的账户凭据、电脑端手机配对记录和 Authenticator 密钥。恢复使用需要重新保存账户密码并绑定。手机和验证器里的旧条目需自行移除。原生 PIN 和 Windows 账户密码不会改变。",
                deleteCredentials: true);
        }
        finally { BtnDeleteCredentials.IsEnabled = true; }
    }

    private async void OnRefreshStateClicked(object sender, RoutedEventArgs e)
        => await RefreshStatusAsync();

    private async void OnTogglePhoneClicked(object sender, RoutedEventArgs e)
    {
        if (_currentState is not { PhoneConfigured: true } state) return;
        await ToggleMethodAsync(state.PhoneEnabled ? "disable-phone" : "enable-phone");
    }

    private async void OnToggleAuthClicked(object sender, RoutedEventArgs e)
    {
        if (_currentState is not { AuthenticatorConfigured: true } state) return;
        await ToggleMethodAsync(state.AuthenticatorEnabled ? "disable-auth" : "enable-auth");
    }

    private async System.Threading.Tasks.Task ToggleMethodAsync(string action)
    {
        try
        {
            await UnlockConfigurationManager.RunAsync(action);
            ShowInfo("设置已更新", "锁屏组件会在下次进入锁屏时使用新状态。", InfoBarSeverity.Success);
            await RefreshStatusAsync();
        }
        catch (Exception ex) { ShowInfo("设置失败", ex.Message, InfoBarSeverity.Error); }
    }

    private async System.Threading.Tasks.Task RemoveMethodAsync(string action, string title, string content, bool deleteCredentials = false)
    {
        var dialog = new ContentDialog
        {
            Title = title,
            Content = content,
            PrimaryButtonText = deleteCredentials ? "删除凭证及绑定" : "确认卸载",
            CloseButtonText = "取消",
            DefaultButton = ContentDialogButton.Close,
            XamlRoot = XamlRoot
        };
        if (await dialog.ShowAsync() != ContentDialogResult.Primary) return;
        try
        {
            await UnlockConfigurationManager.RunAsync(action);
            if (action == "remove-both")
            {
                MainWindow.Current.CurrentAccount = new AccountInfo();
                TxtUsername.Text = string.Empty;
                TxtPassword.Password = string.Empty;
                TxtPassword.PasswordRevealMode = PasswordRevealMode.Hidden;
                IconRevealPassword.Glyph = "\uE7B3";
                TxtRevealPassword.Text = "显示密码";
            }
            ShowInfo(deleteCredentials ? "凭证已删除" : "已卸载",
                deleteCredentials ? "当前账户的登录凭据及两种绑定已清除，需要重新保存密码和绑定才能恢复使用。" : "所选解锁方式已从当前账户移除。",
                InfoBarSeverity.Success);
            await RefreshStatusAsync();
        }
        catch (Exception ex) { ShowInfo(deleteCredentials ? "删除失败" : "卸载失败", ex.Message, InfoBarSeverity.Error); }
    }

    private void ShowInfo(string title, string message, InfoBarSeverity severity)
    {
        SaveInfoBar.Title = title;
        SaveInfoBar.Message = message;
        SaveInfoBar.Severity = severity;
        SaveInfoBar.Visibility = Visibility.Visible;
        SaveInfoBar.IsOpen = true;
        PageScrollViewer.ChangeView(null, 0, null, false);
    }

    private void OnSaveInfoBarClosed(InfoBar sender, InfoBarClosedEventArgs args)
        => sender.Visibility = Visibility.Collapsed;
}
