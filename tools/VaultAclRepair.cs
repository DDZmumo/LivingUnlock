using System;
using System.Collections.Generic;
using System.ComponentModel;
using System.IO;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Text.RegularExpressions;
using Microsoft.Win32.SafeHandles;

namespace LivingUnlock.Maintenance
{
    // Used only by the elevated repair utility. Keep paths pinned until all writes finish.
    public static class VaultAclRepair
    {
        private const uint ReadAttributes = 0x80;
        private const uint EditSecurity = 0xE0000; // READ_CONTROL | WRITE_DAC | WRITE_OWNER
        private const uint OpenReparsePoint = 0x00200000;
        private const uint BackupSemantics = 0x02000000;
        private static readonly Regex RecordName = new Regex(
            @"^(?:S-1-5-21-(?:\d+-){3}\d+\.(?:cred|bin|lock|auth-disabled)|phone_S-1-5-21-(?:\d+-){3}\d+\.(?:dat|disabled))$",
            RegexOptions.CultureInvariant);

        [StructLayout(LayoutKind.Sequential)]
        private struct FileInfo
        {
            public uint Attributes;
            public System.Runtime.InteropServices.ComTypes.FILETIME Created, Accessed, Written;
            public uint Volume, SizeHigh, SizeLow, Links, IndexHigh, IndexLow;
        }

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        private static extern SafeFileHandle CreateFile(string name, uint access, uint share,
            IntPtr security, uint disposition, uint flags, IntPtr template);
        [DllImport("kernel32.dll", SetLastError = true)]
        private static extern bool GetFileInformationByHandle(SafeFileHandle file, out FileInfo info);
        [DllImport("advapi32.dll", SetLastError = true)]
        private static extern bool SetKernelObjectSecurity(SafeFileHandle handle, uint information, byte[] descriptor);

        private static SafeFileHandle Pin(string path, bool directory, bool writable)
        {
            // No FILE_SHARE_DELETE: a checked path component cannot be renamed/replaced.
            var handle = CreateFile(path, ReadAttributes | (writable ? EditSecurity : 0),
                directory ? 3u : 1u, IntPtr.Zero, 3, OpenReparsePoint | BackupSemantics, IntPtr.Zero);
            try
            {
                if (handle.IsInvalid) throw new Win32Exception(Marshal.GetLastWin32Error());
                FileInfo info;
                if (!GetFileInformationByHandle(handle, out info)) throw new Win32Exception(Marshal.GetLastWin32Error());
                if ((info.Attributes & 0x400) != 0) throw new IOException("Reparse points are not allowed: " + path);
                if (((info.Attributes & 0x10) != 0) != directory) throw new IOException("Unexpected entry type: " + path);
                if (!directory && info.Links != 1) throw new IOException("Hard-linked records are not allowed: " + path);
                return handle;
            }
            catch { handle.Dispose(); throw; }
        }

        private static void SetSecurity(SafeFileHandle handle, string sddl)
        {
            var descriptor = new RawSecurityDescriptor(sddl);
            var bytes = new byte[descriptor.BinaryLength];
            descriptor.GetBinaryForm(bytes, 0);
            // Handle-based, non-recursive update: never follow a path again or propagate to unknown children.
            if (!SetKernelObjectSecurity(handle, 0x80000007u, bytes))
                throw new Win32Exception(Marshal.GetLastWin32Error());
        }

        public static int Repair(string directory)
        {
            var path = Path.GetFullPath(directory).TrimEnd(Path.DirectorySeparatorChar);
            if (path.StartsWith(@"\\", StringComparison.Ordinal) || path.Length < 4)
                throw new IOException("Expected a local vault directory, not a volume root or UNC path.");
            var ancestors = new Stack<string>();
            for (var item = new DirectoryInfo(path); item != null; item = item.Parent) ancestors.Push(item.FullName);
            var pinned = new List<SafeFileHandle>();
            var records = new List<SafeFileHandle>();
            try
            {
                while (ancestors.Count != 0)
                {
                    var entry = ancestors.Pop();
                    pinned.Add(Pin(entry, true, ancestors.Count == 0));
                }
                // Validate and pin every candidate before changing any ACL.
                foreach (var entry in Directory.GetFileSystemEntries(path))
                {
                    var attributes = File.GetAttributes(entry);
                    if ((attributes & FileAttributes.ReparsePoint) != 0)
                        throw new IOException("Linked vault entry is not allowed: " + entry);
                    var name = Path.GetFileName(entry);
                    if (!RecordName.IsMatch(name)) continue;
                    var handle = Pin(entry, false, true);
                    pinned.Add(handle);
                    records.Add(handle);
                }
                var rootHandle = pinned[pinned.Count - records.Count - 1];
                SetSecurity(rootHandle, "O:BAG:BAD:P(A;OICI;FA;;;SY)(A;OICI;FA;;;BA)");
                foreach (var record in records)
                    SetSecurity(record, "O:BAG:BAD:P(A;;FA;;;SY)(A;;FA;;;BA)");
                return records.Count;
            }
            finally
            {
                for (int i = pinned.Count - 1; i >= 0; i--) pinned[i].Dispose();
            }
        }
    }
}
