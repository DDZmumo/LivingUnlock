using System;
using LivingUnlock.Windows.Models;
using LivingUnlock.Windows.Services;
using LivingUnlock.Windows.Views;
using Microsoft.UI;
using Microsoft.UI.Windowing;
using Microsoft.UI.Xaml;
using Microsoft.UI.Xaml.Controls;
using Microsoft.UI.Xaml.Media;
using Microsoft.UI.Xaml.Media.Animation;
using Windows.Graphics;

namespace LivingUnlock.Windows;

public sealed partial class MainWindow : Window
{
    public static new MainWindow Current { get; private set; } = null!;
    public VaultService Vault { get; } = new();
    public AccountInfo CurrentAccount { get; set; } = new();
    private WindowWheelRouter? _wheelRouter;

    public MainWindow()
    {
        Current = this;
        CurrentAccount = Vault.DetectCurrentAccountIdentity() ?? Vault.GetSavedAccountIdentity() ?? new AccountInfo();
        InitializeComponent();
        ExtendsContentIntoTitleBar = true;
        SetTitleBar(AppTitleBar);
        ConfigureWindow();
        Activated += (_, _) => _wheelRouter?.AttachChildren();
        RootFrame.Navigated += (_, _) => _wheelRouter?.AttachChildren();
        Closed += (_, _) => _wheelRouter?.Dispose();
        NavigateToHome();
    }

    private void ConfigureWindow()
    {
        var hwnd = WinRT.Interop.WindowNative.GetWindowHandle(this);
        var id = Win32Interop.GetWindowIdFromWindow(hwnd);
        var appWindow = AppWindow.GetFromWindowId(id);
        if (appWindow is null) return;

        _wheelRouter = new WindowWheelRouter(hwnd,
            () => (RootFrame.Content as FrameworkElement)?.FindName("PageScrollViewer") as ScrollViewer);

        appWindow.Title = "LivingUnlock";

        var iconPath = System.IO.Path.Combine(AppContext.BaseDirectory, "Assets", "AppIcon.ico");
        if (System.IO.File.Exists(iconPath))
        {
            appWindow.SetIcon(iconPath);
        }

        int targetWidth = 2000;
        int targetHeight = 1240;

        var area = DisplayArea.GetFromWindowId(id, DisplayAreaFallback.Primary);
        if (area is not null)
        {
            targetWidth = Math.Min(targetWidth, area.WorkArea.Width);
            targetHeight = Math.Min(targetHeight, area.WorkArea.Height);
            appWindow.Resize(new SizeInt32(targetWidth, targetHeight));

            var x = area.WorkArea.X + Math.Max(0, (area.WorkArea.Width - targetWidth) / 2);
            var y = area.WorkArea.Y + Math.Max(0, (area.WorkArea.Height - targetHeight) / 2);
            appWindow.Move(new PointInt32(x, y));
        }
        else
        {
            appWindow.Resize(new SizeInt32(targetWidth, targetHeight));
        }

        _wheelRouter?.SetMinSize(targetWidth, targetHeight);

        var titleBar = appWindow.TitleBar;
        titleBar.ButtonBackgroundColor = Colors.Transparent;
        titleBar.ButtonInactiveBackgroundColor = Colors.Transparent;
        titleBar.ButtonHoverBackgroundColor = global::Windows.UI.Color.FromArgb(90, 255, 255, 255);
        titleBar.ButtonPressedBackgroundColor = global::Windows.UI.Color.FromArgb(120, 16, 39, 62);
        titleBar.ButtonForegroundColor = Colors.White;
        titleBar.ButtonInactiveForegroundColor = global::Windows.UI.Color.FromArgb(180, 255, 255, 255);
    }

    public void NavigateToHome()
    {
        CurrentAccount.Password = string.Empty;
        Navigate(typeof(HomePage), null, "home", SlideNavigationTransitionEffect.FromLeft);
    }

    public void NavigateToAuthenticator(AccountInfo? account = null)
    {
        if (account is not null) CurrentAccount = account;
        EnsureDefaultAccount();
        Navigate(typeof(AuthenticatorPage), CurrentAccount, "auth", SlideNavigationTransitionEffect.FromRight);
    }

    public void NavigateToPairing(AccountInfo? account = null)
    {
        if (account is not null) CurrentAccount = account;
        EnsureDefaultAccount();
        Navigate(typeof(LivingUnlockPairPage), CurrentAccount, "phone", SlideNavigationTransitionEffect.FromRight);
    }

    private void EnsureDefaultAccount()
    {
        if (string.IsNullOrWhiteSpace(CurrentAccount.Username))
        {
            CurrentAccount = Vault.DetectCurrentAccountIdentity() ?? Vault.GetSavedAccountIdentity() ?? new AccountInfo();
        }
    }

    private void Navigate(Type pageType, object? parameter, string destination, SlideNavigationTransitionEffect effect)
    {
        if (RootFrame.CurrentSourcePageType != pageType)
        {
            RootFrame.Navigate(pageType, parameter, new SlideNavigationTransitionInfo { Effect = effect });
        }
        UpdateNavigation(destination);
    }

    private void UpdateNavigation(string destination)
    {
        SetNavState(HomeNavButton, destination == "home");
        SetNavState(AuthenticatorNavButton, destination == "auth");
        SetNavState(PhoneNavButton, destination == "phone");
    }

    private static void SetNavState(Button button, bool selected)
    {
        button.Background = new SolidColorBrush(selected
            ? global::Windows.UI.Color.FromArgb(255, 70, 80, 89)
            : global::Windows.UI.Color.FromArgb(0, 0, 0, 0));
        button.Foreground = new SolidColorBrush(selected
            ? global::Windows.UI.Color.FromArgb(255, 244, 249, 255)
            : global::Windows.UI.Color.FromArgb(255, 184, 193, 204));
        button.BorderThickness = selected ? new Thickness(2, 0, 0, 0) : new Thickness(0);
        button.BorderBrush = new SolidColorBrush(global::Windows.UI.Color.FromArgb(255, 36, 152, 243));
    }

    private void OnHomeNavClicked(object sender, RoutedEventArgs e)
    {
        Motion.Press(HomeNavButton);
        NavigateToHome();
    }

    private void OnAuthenticatorNavClicked(object sender, RoutedEventArgs e)
    {
        Motion.Press(AuthenticatorNavButton);
        if (RootFrame.Content is HomePage home)
        {
            var account = home.GetBindingAccount();
            if (account is null) return;
            NavigateToAuthenticator(account);
        }
        else NavigateToAuthenticator();
    }

    private void OnPhoneNavClicked(object sender, RoutedEventArgs e)
    {
        Motion.Press(PhoneNavButton);
        if (RootFrame.Content is HomePage home)
        {
            var account = home.GetBindingAccount();
            if (account is null) return;
            NavigateToPairing(account);
        }
        else NavigateToPairing();
    }
}
