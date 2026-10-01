using System;
using System.Diagnostics;
using System.IO;
using System.Threading;
using System.Threading.Tasks;

namespace LivingUnlock.Windows.Services;

public class PairingProcessManager
{
    private Process? _process;
    private CancellationTokenSource? _cts;
    private string? _tempSvgPath;

    public event Action<string>? PairingUriReceived;
    public event Action<string>? PairingSucceeded;
    public event Action<string>? PairingFailed;
    public event Action<string>? PairingCancelled;
    public event Action<string>? LogMessageReceived;

    public bool IsRunning => _process != null && !_process.HasExited;

    public static string? FindExecutablePath()
    {
        string baseDir = AppDomain.CurrentDomain.BaseDirectory;
        string[] candidates = new[]
        {
            Path.Combine(baseDir, "phone_pairing_demo.exe"),
            Path.Combine(baseDir, "Assets", "phone_pairing_demo.exe"),
            Path.GetFullPath(Path.Combine(baseDir, @"..\..\..\..\..\build\phone_pairing_demo.exe"))
        };

        foreach (var path in candidates)
        {
            if (File.Exists(path))
            {
                return path;
            }
        }

        return null;
    }

    public async Task StartPairingAsync()
    {
        Stop();

        string? exePath = FindExecutablePath();
        if (exePath == null)
        {
            PairingFailed?.Invoke("找不到配对服务可执行文件 (phone_pairing_demo.exe)。请确认项目编译正常。");
            return;
        }

        _tempSvgPath = Path.Combine(Path.GetTempPath(), $"livingunlock_pair_{Guid.NewGuid():N}.svg");
        _cts = new CancellationTokenSource();

        var psi = new ProcessStartInfo
        {
            FileName = exePath,
            Arguments = $"\"{_tempSvgPath}\"",
            UseShellExecute = false,
            RedirectStandardOutput = true,
            RedirectStandardError = true,
            CreateNoWindow = true
        };

        try
        {
            _process = new Process { StartInfo = psi, EnableRaisingEvents = true };

            _process.OutputDataReceived += (s, e) =>
            {
                if (string.IsNullOrEmpty(e.Data)) return;
                string line = e.Data.Trim();
                LogMessageReceived?.Invoke(line);

                if (line.StartsWith("PAIRING_URI=", StringComparison.OrdinalIgnoreCase))
                {
                    string uri = line.Substring("PAIRING_URI=".Length).Trim();
                    PairingUriReceived?.Invoke(uri);
                }
                else if (line.StartsWith("PAIRING_CANCELLED=", StringComparison.OrdinalIgnoreCase))
                {
                    PairingCancelled?.Invoke(line.Substring("PAIRING_CANCELLED=".Length).Trim());
                }
                else if (line.StartsWith("PAIRING_SUCCESS", StringComparison.OrdinalIgnoreCase))
                {
                    // Will read DEVICE_NAME on next line or parse it
                }
                else if (line.StartsWith("DEVICE_NAME=", StringComparison.OrdinalIgnoreCase))
                {
                    string devName = line.Substring("DEVICE_NAME=".Length).Trim();
                    PairingSucceeded?.Invoke(devName);
                }
            };

            _process.ErrorDataReceived += (s, e) =>
            {
                if (string.IsNullOrEmpty(e.Data)) return;
                string line = e.Data.Trim();
                LogMessageReceived?.Invoke(line);

                if (line.StartsWith("PAIRING_FAILED=", StringComparison.OrdinalIgnoreCase))
                {
                    string err = line.Substring("PAIRING_FAILED=".Length).Trim();
                    PairingFailed?.Invoke(err);
                }
            };

            _process.Exited += (s, e) =>
            {
                CleanupTempFile();
            };

            _process.Start();
            _process.BeginOutputReadLine();
            _process.BeginErrorReadLine();
        }
        catch (Exception ex)
        {
            PairingFailed?.Invoke($"启动配对服务失败: {ex.Message}");
            CleanupTempFile();
        }

        await Task.CompletedTask;
    }

    public void Stop()
    {
        _cts?.Cancel();
        _cts = null;

        if (_process != null)
        {
            try
            {
                if (!_process.HasExited)
                {
                    _process.Kill(true);
                }
            }
            catch { }
            finally
            {
                _process.Dispose();
                _process = null;
            }
        }

        CleanupTempFile();
    }

    private void CleanupTempFile()
    {
        if (_tempSvgPath != null && File.Exists(_tempSvgPath))
        {
            try { File.Delete(_tempSvgPath); } catch { }
            _tempSvgPath = null;
        }
    }
}
