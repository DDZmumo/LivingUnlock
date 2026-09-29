using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.Principal;
using System.Text;
using LivingUnlock.Windows.Models;

namespace LivingUnlock.Windows.Services;

public class VaultService
{
    private const uint LOGON32_LOGON_INTERACTIVE = 2;
    private const uint LOGON32_PROVIDER_DEFAULT = 0;

    private const uint CRYPTPROTECT_UI_FORBIDDEN = 0x1;
    private const uint CRYPTPROTECT_LOCAL_MACHINE = 0x4;

    private const uint MOVEFILE_REPLACE_EXISTING = 0x1;
    private const uint MOVEFILE_WRITE_THROUGH = 0x8;

    [StructLayout(LayoutKind.Sequential)]
    private struct DATA_BLOB
    {
        public int cbData;
        public IntPtr pbData;
    }

    [DllImport("advapi32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool LogonUser(
        string lpszUsername,
        string lpszDomain,
        string lpszPassword,
        uint dwLogonType,
        uint dwLogonProvider,
        out IntPtr phToken);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptProtectData(
        ref DATA_BLOB pDataIn,
        string szDataDescr,
        IntPtr pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        uint dwFlags,
        out DATA_BLOB pDataOut);

    [DllImport("crypt32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool CryptUnprotectData(
        ref DATA_BLOB pDataIn,
        out IntPtr ppszDataDescr,
        IntPtr pOptionalEntropy,
        IntPtr pvReserved,
        IntPtr pPromptStruct,
        uint dwFlags,
        out DATA_BLOB pDataOut);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern IntPtr LocalFree(IntPtr hMem);

    [DllImport("kernel32.dll", SetLastError = true)]
    private static extern bool CloseHandle(IntPtr hObject);

    [DllImport("kernel32.dll", SetLastError = true, CharSet = CharSet.Unicode)]
    private static extern bool MoveFileEx(string lpExistingFileName, string lpNewFileName, uint dwFlags);

    public string CurrentUserSid { get; }
    public string CurrentUsername { get; }
    public string ComputerName { get; }
    public string ProgramDataVaultDir { get; }
    public string LocalAppVaultDir { get; }
    public string VaultDirectory => GetWritableVaultDirectory();

    public VaultService()
    {
        var identity = WindowsIdentity.GetCurrent();
        CurrentUserSid = identity.User?.Value ?? string.Empty;
        CurrentUsername = Environment.UserName;
        ComputerName = Environment.MachineName;

        string programData = Environment.GetFolderPath(Environment.SpecialFolder.CommonApplicationData);
        ProgramDataVaultDir = Path.Combine(programData, "WindowsLockPin");

        string localApp = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
        LocalAppVaultDir = Path.Combine(localApp, "WindowsLockPin");

        try
        {
            if (!Directory.Exists(LocalAppVaultDir))
            {
                Directory.CreateDirectory(LocalAppVaultDir);
            }
        }
        catch { }
    }

    public static byte[]? Unprotect(byte[] cipherText)
    {
        if (cipherText == null || cipherText.Length == 0) return null;

        IntPtr pDataIn = Marshal.AllocHGlobal(cipherText.Length);
        Marshal.Copy(cipherText, 0, pDataIn, cipherText.Length);

        DATA_BLOB inBlob = new DATA_BLOB
        {
            cbData = cipherText.Length,
            pbData = pDataIn
        };

        try
        {
            if (!CryptUnprotectData(
                ref inBlob,
                out IntPtr ppszDescr,
                IntPtr.Zero,
                IntPtr.Zero,
                IntPtr.Zero,
                CRYPTPROTECT_UI_FORBIDDEN,
                out DATA_BLOB outBlob))
            {
                return null;
            }

            if (ppszDescr != IntPtr.Zero)
            {
                LocalFree(ppszDescr);
            }

            byte[] plain = new byte[outBlob.cbData];
            Marshal.Copy(outBlob.pbData, plain, 0, outBlob.cbData);
            LocalFree(outBlob.pbData);
            return plain;
        }
        catch
        {
            return null;
        }
        finally
        {
            Marshal.FreeHGlobal(pDataIn);
        }
    }

    public string? GetPairedPhoneFilePath()
    {
        if (string.IsNullOrEmpty(CurrentUserSid)) return null;
        string filename = $"phone_{CurrentUserSid}.dat";

        string localPath = Path.Combine(LocalAppVaultDir, filename);
        if (File.Exists(localPath)) return localPath;

        try
        {
            string programDataPath = Path.Combine(ProgramDataVaultDir, filename);
            if (File.Exists(programDataPath)) return programDataPath;
        }
        catch { }

        return null;
    }

    public string? GetAuthenticatorFilePath()
    {
        if (string.IsNullOrEmpty(CurrentUserSid)) return null;
        string filename = $"{CurrentUserSid}.bin";

        try
        {
            string programDataPath = Path.Combine(ProgramDataVaultDir, filename);
            if (File.Exists(programDataPath)) return programDataPath;
        }
        catch { }

        string localPath = Path.Combine(LocalAppVaultDir, filename);
        if (File.Exists(localPath)) return localPath;

        return null;
    }

    public string GetWritableVaultDirectory()
    {
        // The client stages records in the per-user vault only. Publishing into
        // ProgramData is the job of the elevated Manage-LivingUnlock.ps1, which
        // applies the SYSTEM/Administrators-only ACL the credential provider
        // requires. Writing directly into ProgramData from here would create a
        // record with an inherited, broadly readable ACL that the provider rejects.
        if (!Directory.Exists(LocalAppVaultDir))
        {
            Directory.CreateDirectory(LocalAppVaultDir);
        }
        return LocalAppVaultDir;
    }

    public bool IsAuthenticatorEnrolled()
    {
        return GetAuthenticatorFilePath() != null;
    }

    public bool IsPhonePaired()
    {
        return GetPairedPhoneInfo() != null;
    }

    public AccountInfo? GetSavedAccountIdentity() => ReadSavedAccount(includePassword: false);

    public AccountInfo? GetSavedAccountForBinding() => ReadSavedAccount(includePassword: true);

    private AccountInfo? ReadSavedAccount(bool includePassword)
    {
        if (string.IsNullOrEmpty(CurrentUserSid)) return null;
        string[] candidates =
        {
            Path.Combine(LocalAppVaultDir, $"{CurrentUserSid}.cred"),
            Path.Combine(ProgramDataVaultDir, $"{CurrentUserSid}.cred"),
            Path.Combine(LocalAppVaultDir, $"{CurrentUserSid}.bin"),
            Path.Combine(ProgramDataVaultDir, $"{CurrentUserSid}.bin")
        };

        foreach (string path in candidates)
        {
            byte[]? plain = null;
            try
            {
                if (!File.Exists(path)) continue;
                plain = Unprotect(File.ReadAllBytes(path));
                if (plain is not { Length: 3504 } ||
                    BitConverter.ToUInt32(plain, 0) != 0x314b504c ||
                    BitConverter.ToUInt32(plain, 4) != 1 ||
                    !string.Equals(ReadFixedWideString(plain, 8, 184), CurrentUserSid,
                        StringComparison.OrdinalIgnoreCase)) continue;

                string qualified = ReadFixedWideString(plain, 376, 512);
                if (string.IsNullOrWhiteSpace(qualified)) continue;
                bool microsoft = qualified.StartsWith("MicrosoftAccount\\", StringComparison.OrdinalIgnoreCase);
                string username = microsoft ? qualified.Substring("MicrosoftAccount\\".Length) : qualified;
                if (string.IsNullOrWhiteSpace(username)) continue;
                return new AccountInfo
                {
                    Type = microsoft ? AccountType.MicrosoftAccount : AccountType.LocalUser,
                    Username = username,
                    Password = includePassword ? ReadFixedWideString(plain, 1400, 1024) : string.Empty
                };
            }
            catch { /* Try the next existing protected or legacy record. */ }
            finally { if (plain is not null) Array.Clear(plain, 0, plain.Length); }
        }
        return null;
    }

    private static string ReadFixedWideString(byte[] data, int offset, int charCount)
    {
        string text = Encoding.Unicode.GetString(data, offset, charCount * sizeof(char));
        int terminator = text.IndexOf('\0');
        return terminator >= 0 ? text[..terminator] : text;
    }

    public PairedPhoneInfo? GetPairedPhoneInfo()
    {
        string? path = GetPairedPhoneFilePath();
        if (path == null) return null;

        try
        {
            byte[] cipherBytes = File.ReadAllBytes(path);
            byte[]? plainBytes = Unprotect(cipherBytes);
            if (plainBytes == null || plainBytes.Length < 688) return null;

            using var reader = new BinaryReader(new MemoryStream(plainBytes));
            uint magic = reader.ReadUInt32();
            if (magic != 0x50484c50) return null; // 'PLHP'
            uint version = reader.ReadUInt32();
            if (version != 1) return null;

            // sid[184] -> 368 bytes
            reader.ReadBytes(184 * 2);

            // pcId[65]
            byte[] pcIdBytes = reader.ReadBytes(65);
            string pcId = ReadNullTerminatedAscii(pcIdBytes);

            // deviceId[65]
            byte[] deviceIdBytes = reader.ReadBytes(65);
            string deviceId = ReadNullTerminatedAscii(deviceIdBytes);

            // deviceName[65]
            byte[] deviceNameBytes = reader.ReadBytes(65);
            string deviceName = ReadNullTerminatedUtf8(deviceNameBytes);

            // bluetoothMac[18]
            byte[] btMacBytes = reader.ReadBytes(18);
            string btMac = ReadNullTerminatedAscii(btMacBytes);

            // clientPublicKey[65]
            reader.ReadBytes(65);
            // kPair[32]
            reader.ReadBytes(32);

            // Structure has 2 bytes of padding before uint64 timestamp (offset 688)
            reader.BaseStream.Seek(688, SeekOrigin.Begin);
            ulong timestamp = reader.ReadUInt64();

            DateTime pairedAt = timestamp > 0
                ? DateTimeOffset.FromUnixTimeSeconds((long)timestamp).LocalDateTime
                : DateTime.Now;

            return new PairedPhoneInfo
            {
                DeviceName = string.IsNullOrWhiteSpace(deviceName) ? "未知设备" : deviceName,
                DeviceId = deviceId,
                PcId = pcId,
                BluetoothMac = btMac,
                PairedAt = pairedAt
            };
        }
        catch
        {
            return null;
        }
    }

    private static string ReadNullTerminatedAscii(byte[] bytes)
    {
        int nullIdx = Array.IndexOf(bytes, (byte)0);
        int len = nullIdx >= 0 ? nullIdx : bytes.Length;
        return Encoding.ASCII.GetString(bytes, 0, len).Trim();
    }

    private static string ReadNullTerminatedUtf8(byte[] bytes)
    {
        int nullIdx = Array.IndexOf(bytes, (byte)0);
        int len = nullIdx >= 0 ? nullIdx : bytes.Length;
        return Encoding.UTF8.GetString(bytes, 0, len).Trim();
    }

    public bool RemovePairedPhone()
    {
        if (string.IsNullOrEmpty(CurrentUserSid)) return false;
        string filename = $"phone_{CurrentUserSid}.dat";
        bool removed = false;

        string localPath = Path.Combine(LocalAppVaultDir, filename);
        if (File.Exists(localPath))
        {
            try
            {
                File.Delete(localPath);
                removed = true;
            }
            catch { }
        }

        try
        {
            string progPath = Path.Combine(ProgramDataVaultDir, filename);
            if (File.Exists(progPath))
            {
                File.Delete(progPath);
                removed = true;
            }
        }
        catch { }

        return removed;
    }

    public bool RemoveAuthenticator()
    {
        if (string.IsNullOrEmpty(CurrentUserSid)) return false;
        string filename = $"{CurrentUserSid}.bin";
        bool removed = false;

        string localPath = Path.Combine(LocalAppVaultDir, filename);
        if (File.Exists(localPath))
        {
            try
            {
                File.Delete(localPath);
                removed = true;
            }
            catch { }
        }

        try
        {
            string progPath = Path.Combine(ProgramDataVaultDir, filename);
            if (File.Exists(progPath))
            {
                File.Delete(progPath);
                removed = true;
            }
        }
        catch { }

        return removed;
    }

    public (bool Success, string ErrorMessage) ValidateWindowsCredentials(AccountInfo account)
    {
        if (string.IsNullOrWhiteSpace(account.Username))
            return (false, "请输入用户名或微软账户邮箱。");

        if (string.IsNullOrEmpty(account.Password))
            return (false, "请输入该账户的 Windows 登录密码或 PIN。");

        string domain = account.Type == AccountType.MicrosoftAccount ? "MicrosoftAccount" : ".";
        string user = account.Username.Trim();

        // If Microsoft account has prefix, strip it for LogonUser
        if (account.Type == AccountType.MicrosoftAccount && user.StartsWith("MicrosoftAccount\\", StringComparison.OrdinalIgnoreCase))
        {
            user = user.Substring("MicrosoftAccount\\".Length);
        }

        IntPtr token = IntPtr.Zero;
        try
        {
            bool ok = LogonUser(user, domain, account.Password, LOGON32_LOGON_INTERACTIVE, LOGON32_PROVIDER_DEFAULT, out token);
            if (!ok)
            {
                int error = Marshal.GetLastWin32Error();
                return (false, $"Windows 账户验证失败（系统错误码 0x{error:X}），请核对用户名与密码。");
            }

            var identity = new WindowsIdentity(token);
            if (identity.User == null || !string.Equals(identity.User.Value, CurrentUserSid, StringComparison.OrdinalIgnoreCase))
            {
                return (false, $"输入的账户 SID ({identity.User?.Value}) 与当前登录用户 ({CurrentUserSid}) 不符，请录入当前正在登录的账户。");
            }

            return (true, string.Empty);
        }
        catch (Exception ex)
        {
            return (false, $"验证过程出现异常: {ex.Message}");
        }
        finally
        {
            if (token != IntPtr.Zero)
            {
                CloseHandle(token);
            }
        }
    }

    public (bool Success, string ErrorMessage) SaveUnlockCredentials(AccountInfo account)
    {
        // Phone unlock needs the Windows credentials, but must not create an
        // Authenticator enrollment or replace an existing TOTP secret.
        return SaveRecord(account, new byte[20], 0, ".cred");
    }

    public (bool Success, string ErrorMessage) SaveAuthenticatorEnrollment(AccountInfo account, byte[] secret, long matchedStep)
        => SaveRecord(account, secret, matchedStep, ".bin");

    private (bool Success, string ErrorMessage) SaveRecord(AccountInfo account, byte[] secret, long matchedStep, string extension)
    {
        if (secret == null || secret.Length != 20)
            return (false, "无效的 TOTP 密钥数据。");

        // Validate credentials first
        var validation = ValidateWindowsCredentials(account);
        if (!validation.Success)
            return validation;

        // Build Record byte structure (3504 bytes)
        byte[] recordBytes = new byte[3504];
        using var ms = new MemoryStream(recordBytes);
        using var writer = new BinaryWriter(ms);

        // uint32 magic = 0x314b504c
        writer.Write((uint)0x314b504c);
        // uint32 version = 1
        writer.Write((uint)1);

        // wchar_t sid[184] -> 368 bytes
        WriteFixedWideString(writer, CurrentUserSid, 184);

        // wchar_t username[512] -> 1024 bytes
        string fullUsername = account.QualifiedUsername;
        WriteFixedWideString(writer, fullUsername, 512);

        // wchar_t password[1024] -> 2048 bytes
        WriteFixedWideString(writer, account.Password, 1024);

        // unsigned char secret[20] -> 20 bytes
        writer.Write(secret);

        // 4 bytes alignment padding
        writer.Write((uint)0);

        // Policy struct -> 32 bytes
        ulong now = (ulong)DateTimeOffset.UtcNow.ToUnixTimeSeconds();
        // uint64 lastSeen
        writer.Write(now);
        // uint64 blockedUntil
        writer.Write((ulong)0);
        // uint64 lastStep
        writer.Write((ulong)matchedStep);
        // uint32 failures
        writer.Write((uint)0);
        // uint32 hasLastStep
        writer.Write((uint)1);

        writer.Flush();

        // Encrypt with Machine DPAPI
        IntPtr pDataIn = Marshal.AllocHGlobal(recordBytes.Length);
        Marshal.Copy(recordBytes, 0, pDataIn, recordBytes.Length);

        DATA_BLOB inBlob = new DATA_BLOB
        {
            cbData = recordBytes.Length,
            pbData = pDataIn
        };

        try
        {
            bool ok = CryptProtectData(
                ref inBlob,
                "WindowsLockPin v1",
                IntPtr.Zero,
                IntPtr.Zero,
                IntPtr.Zero,
                CRYPTPROTECT_LOCAL_MACHINE | CRYPTPROTECT_UI_FORBIDDEN,
                out DATA_BLOB outBlob);

            if (!ok)
            {
                int err = Marshal.GetLastWin32Error();
                return (false, $"DPAPI 加密保存失败 (错误码 0x{err:X})。");
            }

            byte[] encrypted = new byte[outBlob.cbData];
            Marshal.Copy(outBlob.pbData, encrypted, 0, outBlob.cbData);
            LocalFree(outBlob.pbData);

            string targetDir = GetWritableVaultDirectory();
            string targetPath = Path.Combine(targetDir, $"{CurrentUserSid}{extension}");
            string pendingPath = targetPath + ".pending";

            try
            {
                File.WriteAllBytes(pendingPath, encrypted);
                if (!MoveFileEx(pendingPath, targetPath, MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH))
                {
                    File.Copy(pendingPath, targetPath, true);
                    File.Delete(pendingPath);
                }

                // ProgramData publishing is performed exclusively by the elevated
                // Manage-LivingUnlock.ps1 (promote-*), which sets the strict ACL.
                return (true, string.Empty);
            }
            catch (Exception ex)
            {
                return (false, $"写入保险库文件失败: {ex.Message}");
            }
        }
        finally
        {
            // Wipe memory
            Array.Clear(recordBytes, 0, recordBytes.Length);
            Marshal.FreeHGlobal(pDataIn);
        }
    }

    private static void WriteFixedWideString(BinaryWriter writer, string text, int maxChars)
    {
        byte[] buffer = new byte[maxChars * 2];
        if (!string.IsNullOrEmpty(text))
        {
            byte[] encoded = Encoding.Unicode.GetBytes(text);
            int copyLen = Math.Min(encoded.Length, (maxChars - 1) * 2);
            Buffer.BlockCopy(encoded, 0, buffer, 0, copyLen);
        }
        writer.Write(buffer);
    }
}
