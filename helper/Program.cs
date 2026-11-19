// Amirus Dictator helper: a tray app that types dictated text into whichever Windows app has focus.
// The web app sends each finished phrase to http://127.0.0.1:48219; only the origins listed below are accepted.
// Build (no SDK needed, uses the compiler that ships with Windows): see helper\build.cmd
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Win32;

static class Program
{
    const int Port = 48219;
    const string AppUrl = "https://amirusdev.github.io/AmirusDictator/";
    const string RunKey = @"Software\Microsoft\Windows\CurrentVersion\Run";
    const string RunName = "AmirusDictatorHelper";
    const string IdleTip = "Amirus Dictator: Ctrl+Alt+D mic, Ctrl+Alt+L language";
    // Browsers always send the page's real origin, so other websites cannot make this app type.
    static readonly string[] Origins = { "https://amirusdev.github.io", "http://localhost:5173", "http://127.0.0.1:5173" };

    static readonly List<NetworkStream> clients = new List<NetworkStream>();
    // "Mini" clients are the small always-on-top mic window this helper opens; hotkeys go only to them.
    static readonly List<NetworkStream> minis = new List<NetworkStream>();
    static volatile bool pendingToggle;
    static volatile bool miniLive;
    static DateTime hideAt = DateTime.MaxValue, closeAt = DateTime.MaxValue;
    static IntPtr lastTarget = IntPtr.Zero;
    static readonly object typeLock = new object();
    static NotifyIcon tray;
    static Icon idleIcon, liveIcon;
    static Control uiThread;
    static System.Threading.Timer keepAlive;

    [STAThread]
    static void Main()
    {
        bool first;
        var mutex = new Mutex(true, "AmirusDictatorHelper.Single", out first);
        if (!first) return;

        Application.EnableVisualStyles();
        TcpListener listener;
        try { listener = new TcpListener(IPAddress.Loopback, Port); listener.Start(); }
        catch (SocketException)
        {
            MessageBox.Show("Port " + Port + " is in use by another program, so the helper cannot start.", "Amirus Dictator helper", MessageBoxButtons.OK, MessageBoxIcon.Warning);
            return;
        }

        idleIcon = MakeIcon(Color.FromArgb(15, 118, 110));
        liveIcon = MakeIcon(Color.FromArgb(217, 45, 32));
        uiThread = new Control();
        IntPtr unused = uiThread.Handle;

        var menu = new ContextMenuStrip();
        menu.Items.Add("Open Amirus Dictator", null, delegate { OpenApp(); });
        var startup = new ToolStripMenuItem("Start with Windows");
        startup.Checked = IsStartup();
        startup.Click += delegate { SetStartup(!startup.Checked); startup.Checked = IsStartup(); };
        menu.Items.Add(startup);
        menu.Items.Add(new ToolStripSeparator());
        menu.Items.Add("Exit", null, delegate { tray.Visible = false; Application.Exit(); });

        tray = new NotifyIcon();
        tray.Icon = idleIcon;
        tray.Text = IdleTip;
        tray.ContextMenuStrip = menu;
        tray.Visible = true;
        tray.DoubleClick += delegate { OpenApp(); };

        var hot = new HotWindow();
        string tip = hot.Ok
            ? "Click into any app, then press Ctrl+Alt+D to start or stop dictating. Ctrl+Alt+L switches language."
            : "Running, but Ctrl+Alt+D or Ctrl+Alt+L is taken by another program. Use the mic button in the Dictator window.";
        tray.ShowBalloonTip(5000, "Amirus Dictator helper is running", tip, ToolTipIcon.Info);

        var accept = new Thread(delegate ()
        {
            while (true)
            {
                try { ThreadPool.QueueUserWorkItem(Handle, listener.AcceptTcpClient()); }
                catch { return; }
            }
        });
        accept.IsBackground = true;
        accept.Start();
        keepAlive = new System.Threading.Timer(delegate { Broadcast(": ping\n\n"); }, null, 20000, 20000);
        var tick = new System.Threading.Timer(delegate { MiniTick(); }, null, 1000, 1000);

        Application.Run();
        tray.Visible = false;
        listener.Stop();
        GC.KeepAlive(mutex);
        GC.KeepAlive(hot);
        GC.KeepAlive(tick);
    }

    // ---------- tiny HTTP server ----------
    static void Handle(object state)
    {
        var client = (TcpClient)state;
        bool keep = false;
        try
        {
            client.NoDelay = true;
            NetworkStream s = client.GetStream();
            s.ReadTimeout = 5000;

            var head = new MemoryStream();
            int match = 0;
            while (match < 4 && head.Length < 16384)
            {
                int b = s.ReadByte();
                if (b < 0) return;
                head.WriteByte((byte)b);
                match = (b == (match % 2 == 0 ? '\r' : '\n')) ? match + 1 : (b == '\r' ? 1 : 0);
            }
            string[] lines = Encoding.ASCII.GetString(head.ToArray()).Split(new[] { "\r\n" }, StringSplitOptions.RemoveEmptyEntries);
            if (lines.Length == 0) return;
            string[] request = lines[0].Split(' ');
            if (request.Length < 2) return;
            string method = request[0], path = request[1], origin = null;
            int length = 0;
            for (int i = 1; i < lines.Length; i++)
            {
                int c = lines[i].IndexOf(':');
                if (c < 1) continue;
                string name = lines[i].Substring(0, c).Trim().ToLowerInvariant(), value = lines[i].Substring(c + 1).Trim();
                if (name == "origin") origin = value;
                else if (name == "content-length") int.TryParse(value, out length);
            }
            if (origin == null || Array.IndexOf(Origins, origin) < 0) { Reply(s, "403 Forbidden", null); return; }
            if (length < 0 || length > 200000) { Reply(s, "413 Payload Too Large", origin); return; }

            var body = new byte[length];
            int got = 0;
            while (got < length)
            {
                int n = s.Read(body, got, length - got);
                if (n <= 0) break;
                got += n;
            }
            string text = Encoding.UTF8.GetString(body, 0, got);

            if (method == "OPTIONS") Reply(s, "204 No Content", origin);
            else if (method == "GET" && path.StartsWith("/events"))
            {
                bool mini = path.Contains("mini=1");
                Write(s, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nCache-Control: no-cache\r\nConnection: keep-alive\r\n" + Cors(origin) + "\r\ndata: hello\n\n");
                lock (clients)
                {
                    clients.Add(s);
                    if (mini) minis.Add(s);
                }
                keep = true;
                if (mini)
                {
                    ThreadPool.QueueUserWorkItem(delegate { ArrangeMini(); });
                    if (pendingToggle)
                    {
                        pendingToggle = false;
                        try { Write(s, "data: toggle\n\n"); } catch { }
                    }
                }
            }
            else if (method == "POST" && path == "/type") { TypeText(text); Reply(s, "204 No Content", origin); }
            else if (method == "POST" && path.StartsWith("/state"))
            {
                bool live = text.StartsWith("on");
                SetLive(live);
                if (path.Contains("mini=1")) MiniState(live, text.StartsWith("error"));
                Reply(s, "204 No Content", origin);
            }
            else Reply(s, "404 Not Found", origin);
        }
        catch { }
        finally { if (!keep) client.Close(); }
    }

    static string Cors(string origin)
    {
        return "Access-Control-Allow-Origin: " + origin + "\r\nVary: Origin\r\nAccess-Control-Allow-Methods: GET, POST, OPTIONS\r\n" +
               "Access-Control-Allow-Headers: Content-Type\r\nAccess-Control-Allow-Private-Network: true\r\nAccess-Control-Max-Age: 600\r\n";
    }

    static void Reply(NetworkStream s, string status, string origin)
    {
        Write(s, "HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n" + (origin == null ? "" : Cors(origin)) + "\r\n");
    }

    static void Write(NetworkStream s, string text)
    {
        byte[] bytes = Encoding.UTF8.GetBytes(text);
        s.Write(bytes, 0, bytes.Length);
        s.Flush();
    }

    static int ClientCount() { lock (clients) return clients.Count; }

    static void Broadcast(string message)
    {
        bool empty;
        lock (clients)
        {
            for (int i = clients.Count - 1; i >= 0; i--)
            {
                try { Write(clients[i], message); }
                catch { minis.Remove(clients[i]); clients[i].Dispose(); clients.RemoveAt(i); }
            }
            empty = clients.Count == 0;
        }
        if (empty) SetLive(false);
    }

    // ---------- tray ----------
    static void SetLive(bool live)
    {
        try
        {
            uiThread.BeginInvoke((MethodInvoker)delegate
            {
                tray.Icon = live ? liveIcon : idleIcon;
                tray.Text = live ? "Amirus Dictator: listening (Ctrl+Alt+D to stop)" : IdleTip;
            });
        }
        catch { }
    }

    static void OpenApp()
    {
        try { Process.Start(AppUrl); } catch { }
    }

    public static void Hotkey(int id)
    {
        string message = "data: " + (id == 1 ? "toggle" : "lang") + "\n\n";
        bool sent = false;
        lock (clients)
        {
            foreach (NetworkStream s in minis)
            {
                try { Write(s, message); sent = true; } catch { }
            }
        }
        if (sent) { if (id == 1) ShowMini(); return; }
        if (id != 1) return;
        // No mic window yet: open one and start listening as soon as it connects.
        lastTarget = GetForegroundWindow();
        pendingToggle = true;
        LaunchMini();
    }

    // ---------- the small always-on-top mic window ----------
    const string MiniTitle = "Amirus Dictator Mic";
    const int MiniW = 340, MiniH = 168;

    delegate bool EnumProc(IntPtr hwnd, IntPtr lParam);
    [DllImport("user32.dll")] static extern bool EnumWindows(EnumProc proc, IntPtr lParam);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetWindowText(IntPtr hwnd, StringBuilder text, int max);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr hwnd, StringBuilder text, int max);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern bool SetForegroundWindow(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool IsWindow(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool IsWindowVisible(IntPtr hwnd);
    [DllImport("user32.dll")] static extern bool ShowWindow(IntPtr hwnd, int cmd);
    [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr hwnd, IntPtr after, int x, int y, int w, int h, uint flags);
    [DllImport("user32.dll", EntryPoint = "GetWindowLongPtrW")] static extern IntPtr GetWindowLongPtr(IntPtr hwnd, int index);
    [DllImport("user32.dll", EntryPoint = "SetWindowLongPtrW")] static extern IntPtr SetWindowLongPtr(IntPtr hwnd, int index, IntPtr value);
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] static extern bool AttachThreadInput(uint from, uint to, bool attach);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] static extern bool PostMessage(IntPtr hwnd, uint msg, IntPtr w, IntPtr l);

    static IntPtr FindMini()
    {
        IntPtr found = IntPtr.Zero;
        EnumWindows(delegate (IntPtr hwnd, IntPtr l)
        {
            var title = new StringBuilder(128);
            GetWindowText(hwnd, title, 128);
            if (title.ToString().IndexOf(MiniTitle, StringComparison.Ordinal) < 0) return true;
            var cls = new StringBuilder(64);
            GetClassName(hwnd, cls, 64);
            if (!cls.ToString().StartsWith("Chrome_WidgetWin")) return true;
            found = hwnd;
            return false;
        }, IntPtr.Zero);
        return found;
    }

    static string BrowserPath()
    {
        foreach (string exe in new[] { "chrome.exe", "msedge.exe" })
        {
            foreach (RegistryKey root in new[] { Registry.CurrentUser, Registry.LocalMachine })
            {
                using (RegistryKey key = root.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\App Paths\" + exe))
                {
                    string path = key == null ? null : key.GetValue(null) as string;
                    if (path != null && File.Exists(path)) return path;
                }
            }
        }
        return null;
    }

    static void LaunchMini()
    {
        Rectangle area = Screen.PrimaryScreen.WorkingArea;
        string url = AppUrl + "?mini=1";
        try
        {
            string browser = BrowserPath();
            if (browser == null) Process.Start(url);
            else Process.Start(browser, "--app=" + url + " --window-size=" + MiniW + "," + MiniH +
                " --window-position=" + (area.Right - MiniW - 16) + "," + (area.Bottom - MiniH - 16));
        }
        catch { pendingToggle = false; }
    }

    // Makes the mic window a small, always-on-top tool window that never takes keyboard focus,
    // then hands focus back to the app the user was typing in.
    static void ArrangeMini()
    {
        IntPtr hwnd = IntPtr.Zero;
        for (int i = 0; i < 40 && hwnd == IntPtr.Zero; i++)
        {
            hwnd = FindMini();
            if (hwnd == IntPtr.Zero) Thread.Sleep(150);
        }
        if (hwnd == IntPtr.Zero) return;
        Rectangle area = Screen.PrimaryScreen.WorkingArea;
        const int GWL_EXSTYLE = -20;
        const long WS_EX_TOOLWINDOW = 0x80, WS_EX_NOACTIVATE = 0x08000000, WS_EX_APPWINDOW = 0x40000;
        long ex = GetWindowLongPtr(hwnd, GWL_EXSTYLE).ToInt64();
        ShowWindow(hwnd, 0);
        SetWindowLongPtr(hwnd, GWL_EXSTYLE, new IntPtr((ex | WS_EX_TOOLWINDOW | WS_EX_NOACTIVATE) & ~WS_EX_APPWINDOW));
        SetWindowPos(hwnd, new IntPtr(-1), area.Right - MiniW - 16, area.Bottom - MiniH - 16, MiniW, MiniH, 0x0010 | 0x0040);
        hideAt = DateTime.MaxValue;
        closeAt = DateTime.UtcNow.AddMinutes(3);
        IntPtr target = lastTarget;
        if (target == IntPtr.Zero || !IsWindow(target) || GetForegroundWindow() == target) return;
        // Windows only lets the foreground thread hand focus on, so borrow its input queue for the call.
        uint pid, mine = GetCurrentThreadId(), theirs = GetWindowThreadProcessId(GetForegroundWindow(), out pid);
        AttachThreadInput(mine, theirs, true);
        SetForegroundWindow(target);
        AttachThreadInput(mine, theirs, false);
    }

    static void ShowMini()
    {
        IntPtr hwnd = FindMini();
        if (hwnd == IntPtr.Zero) return;
        hideAt = DateTime.MaxValue;
        ShowWindow(hwnd, 4);                                                       // SW_SHOWNOACTIVATE
        SetWindowPos(hwnd, new IntPtr(-1), 0, 0, 0, 0, 0x0001 | 0x0002 | 0x0010);  // keep on top; no move, size or activate
    }

    static void MiniState(bool live, bool error)
    {
        miniLive = live;
        if (live) { ShowMini(); closeAt = DateTime.MaxValue; return; }
        // Leave time for the last phrase to arrive (or an error to be read) before hiding.
        hideAt = DateTime.UtcNow.AddSeconds(error ? 15 : 2);
        closeAt = DateTime.UtcNow.AddMinutes(3);
    }

    static void MiniTick()
    {
        if (miniLive) return;
        DateTime now = DateTime.UtcNow;
        if (now < hideAt && now < closeAt) return;
        IntPtr hwnd = FindMini();
        if (hwnd == IntPtr.Zero) { hideAt = closeAt = DateTime.MaxValue; return; }
        if (now >= closeAt)
        {
            // Close an idle mic window before Chrome freezes it; the next hotkey opens a fresh one.
            hideAt = closeAt = DateTime.MaxValue;
            PostMessage(hwnd, 0x0010, IntPtr.Zero, IntPtr.Zero);
        }
        else
        {
            hideAt = DateTime.MaxValue;
            if (IsWindowVisible(hwnd)) ShowWindow(hwnd, 0);
        }
    }

    static bool IsStartup()
    {
        using (RegistryKey key = Registry.CurrentUser.OpenSubKey(RunKey)) return key != null && key.GetValue(RunName) != null;
    }

    static void SetStartup(bool on)
    {
        using (RegistryKey key = Registry.CurrentUser.CreateSubKey(RunKey))
        {
            if (on) key.SetValue(RunName, "\"" + Application.ExecutablePath + "\"");
            else key.DeleteValue(RunName, false);
        }
    }

    static Icon MakeIcon(Color back)
    {
        using (var bmp = new Bitmap(32, 32))
        using (Graphics g = Graphics.FromImage(bmp))
        {
            g.SmoothingMode = SmoothingMode.AntiAlias;
            using (var fill = new SolidBrush(back)) g.FillEllipse(fill, 1, 1, 30, 30);
            using (var body = new GraphicsPath())
            {
                body.AddArc(12f, 6f, 8f, 8f, 180f, 180f);
                body.AddArc(12f, 11f, 8f, 8f, 0f, 180f);
                body.CloseFigure();
                g.FillPath(Brushes.White, body);
            }
            using (var pen = new Pen(Color.White, 2f))
            {
                g.DrawArc(pen, 9f, 8f, 14f, 14f, 0f, 180f);
                g.DrawLine(pen, 16f, 22f, 16f, 26f);
            }
            return Icon.FromHandle(bmp.GetHicon());
        }
    }

    // ---------- typing ----------
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx, dy; public uint mouseData, dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk, wScan; public uint dwFlags, time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion u; }

    [DllImport("user32.dll", SetLastError = true)]
    static extern uint SendInput(uint count, INPUT[] inputs, int size);
    [DllImport("user32.dll")]
    static extern short GetAsyncKeyState(int key);

    static INPUT Key(ushort vk, ushort scan, uint flags)
    {
        var input = new INPUT();
        input.type = 1;
        input.u.ki.wVk = vk;
        input.u.ki.wScan = scan;
        input.u.ki.dwFlags = flags;
        return input;
    }

    static bool ModifierDown()
    {
        return GetAsyncKeyState(0x11) < 0 || GetAsyncKeyState(0x12) < 0 || GetAsyncKeyState(0x5B) < 0 || GetAsyncKeyState(0x5C) < 0;
    }

    static void Flush(List<INPUT> batch)
    {
        if (batch.Count == 0) return;
        SendInput((uint)batch.Count, batch.ToArray(), Marshal.SizeOf(typeof(INPUT)));
        batch.Clear();
        Thread.Sleep(6);
    }

    static void TypeText(string text)
    {
        lock (typeLock)
        {
            // If Ctrl/Alt/Win is still held (e.g. from the hotkey), typing would fire shortcuts instead of text.
            for (int i = 0; i < 30 && ModifierDown(); i++) Thread.Sleep(50);
            var batch = new List<INPUT>();
            foreach (char ch in text)
            {
                if (ch == '\r') continue;
                if (ch == '\n') { batch.Add(Key(0x0D, 0, 0)); batch.Add(Key(0x0D, 0, 2)); }
                else { batch.Add(Key(0, ch, 4)); batch.Add(Key(0, ch, 4 | 2)); }
                if (batch.Count >= 64) Flush(batch);
            }
            Flush(batch);
        }
    }
}

sealed class HotWindow : NativeWindow
{
    [DllImport("user32.dll")]
    static extern bool RegisterHotKey(IntPtr hwnd, int id, uint modifiers, uint key);

    public readonly bool Ok;

    public HotWindow()
    {
        CreateHandle(new CreateParams());
        const uint CtrlAltNoRepeat = 0x4003;
        bool mic = RegisterHotKey(Handle, 1, CtrlAltNoRepeat, 0x44);   // Ctrl+Alt+D
        bool lang = RegisterHotKey(Handle, 2, CtrlAltNoRepeat, 0x4C);  // Ctrl+Alt+L
        Ok = mic && lang;
    }

    protected override void WndProc(ref Message m)
    {
        if (m.Msg == 0x0312) Program.Hotkey(m.WParam.ToInt32());
        base.WndProc(ref m);
    }
}
